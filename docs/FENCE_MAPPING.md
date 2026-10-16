# fence_mapping.json

The file that tells PSAMA (`pic-sure-auth-microapp`) which consent groups exist and which FENCE
authorization grants each one. It decides which HPDS partitions a user can query, so a bad file
silently locks everyone out or fails PSAMA at startup. PSAMA has no validation of its own; this
repo supplies one.

- Schema: `src/main/resources/schemas/fence_mapping.schema.json` (JSON Schema 2020-12)
- Validator: `core/fencemapping/FenceMappingValidator` (schema plus coded cross-entry checks)
- Job: `validate-fence-mapping --input <uri>` exits 2 on any error
- Also used, report-only, by `split-allconcepts-by-consent` (see `docs/SPLIT_ALLCONCEPTS_BY_CONSENT.md`)

```bash
java -jar target/hpds-etl.jar --job=validate-fence-mapping --input=./fence_mapping.json
java -jar target/hpds-etl.jar --job=validate-fence-mapping --input=s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/general/data/metadata_new_search.json
```

Line references below are to `pic-sure-auth-microapp/pic-sure-auth-services/src/main/java/edu/harvard/hms/dbmi/avillach/auth/`
at the checkout this was written against (`ecf5a5315`); they will drift.

## What PSAMA loads, and when

`utils/FenceMappingUtility.java`:

- Once, in `@PostConstruct` at startup (`:33-49`). Never reloaded: a changed file needs a restart.
- Reads `${application.template.path}fence_mapping.json` (`:37`). The path must end with a file
  separator (`:35`). A missing file is only logged (`:38-39`), and the maps stay `null`.
- Parses it with a default Jackson `ObjectMapper` (`:42`) into `BioDataCatalyst`
  (`model/fenceMapping/BioDataCatalyst.java`, root property `bio_data_catalyst`) of `StudyMetaData`
  (`model/fenceMapping/StudyMetaData.java:11-79`).
- Builds two maps (`:51-68`), both overwriting on a repeated key:
  - by consent key (`:58-61`): `study_identifier + "." + consent_group_code`, or just
    `study_identifier` when the code is null or empty;
  - by authZ (`:62`): `authZ` with `\/` replaced by `/`.

Where the maps are used:

- `service/impl/authentication/FENCEAuthenticationService.java:140-161`: after a FENCE login, each
  authz path the user holds is looked up in the by-authZ map (`:146`); an unknown path is logged and
  skipped (`:147-150`); a known one becomes the consent string `phs123456.c1` (`:152-157`), which is
  stored on the user (`:161`).
- `service/impl/authorization/BdcConsentsBuilder.java:35-60`: the user's consent strings that are
  keys of the by-consent map are kept (`:38-46`), every entry whose `study_type` is `public` is
  added for everyone (`:49-53`), and an empty result throws `IllegalStateException` (`:55-57`).
  Called from `service/impl/UserService.java:633`.

HPDS then serves only the partition directories whose name equals one of those consent strings.
So a partition named `phs123456.c1` is reachable only if an entry yields exactly that key.

## Used versus ignored fields

`StudyMetaData` has 23 fields. PSAMA reads four.

| Field | Used by PSAMA | For |
|---|---|---|
| `study_identifier` | yes | first half of the consent key (`FenceMappingUtility:58-60`); consent string (`FENCEAuthenticationService:152`) |
| `consent_group_code` | yes | second half of the key; empty or null means the study-level key (`:58`) |
| `study_type` | yes | `public` (case-insensitive) grants the entry to every user (`BdcConsentsBuilder:49-53`) |
| `authZ` | yes | FENCE login lookup key (`FenceMappingUtility:62`, `FENCEAuthenticationService:146`) |
| `abbreviated_name`, `full_study_name`, `consent_group_name_abv`, `consent_group_name`, `request_access`, `data_type`, `study_version`, `study_phase`, `top_level_path`, `study_focus`, `study_design`, `additional_information` | no | parsed, never read in main code |
| `clinical_variable_count`, `genetic_sample_size`, `clinical_sample_size`, `raw_clinical_variable_count`, `raw_genetic_sample_size`, `raw_clinical_sample_size` | no | parsed into primitive `int`; a non-numeric value rejects the whole file |
| `is_harmonized` | no | parsed with `IsHarmonizedDeserializer` (`Y`/`Yes` is true, anything else false); only tests set it |

The 19 ignored fields still have to be spelled exactly right, because of the next section.

## Schema rules, their source in PSAMA, and what breaks

| Rule in `fence_mapping.schema.json` | PSAMA behaviour | What breaks without it |
|---|---|---|
| Root is an object with only `bio_data_catalyst` (`additionalProperties: false`) | default `ObjectMapper` has `FAIL_ON_UNKNOWN_PROPERTIES` on (`FenceMappingUtility:42`, `BioDataCatalyst`) | one unknown root property rejects the file |
| `bio_data_catalyst` is required, an array | `getStudyMetaData()` is `null` otherwise; `studies.size()` throws inside the `try` (`:88-89`) | caught and swallowed (below): empty mapping |
| Items are objects with only the 23 `StudyMetaData` names (`additionalProperties: false`) | same `FAIL_ON_UNKNOWN_PROPERTIES` | **one unknown field in one entry rejects the whole file.** The `catch` at `:90-92` logs and returns an empty list, startup continues, and no user is granted any consent (`BdcConsentsBuilder:55-57` then throws for every login) |
| The six count fields are integers (>= -1) | primitive `int` fields | a string such as `"many"` or a fraction fails deserialization: whole file rejected |
| `is_harmonized` is a string or null | `IsHarmonizedDeserializer` reads text | cosmetic only; typed string to match what the generator writes |
| Optional strings are `string` or `null` | Jackson accepts null for `String` | none; the schema allows it because PSAMA does |
| `authZ` required, a non-null string | `study.getAuthZ().replace(...)` (`:62`) | **null or absent `authZ` throws `NullPointerException` inside the `forEach` in `@PostConstruct`: PSAMA fails to start** |
| `study_identifier` required, `minLength` 1 | key built from it (`:58-60`) | empty gives keys like `.c1` that no partition matches |
| `consent_group_code` required, `^(c[0-9]+)?$` or null | empty or null gives the study-level key (`:58`) | any other shape yields a key such as `phs123456.1` that no HPDS partition (`phs123456.c1`) matches |
| `study_type` required, a string | only `public` matters (`BdcConsentsBuilder:50`) | an absent type is silently non-public; required so the intent is explicit |

Rules a schema cannot express, enforced in `FenceMappingValidator`:

| Check | Code | PSAMA behaviour | What breaks |
|---|---|---|---|
| Key `study_identifier[.consent_group_code]` is unique | `FENCE_DUPLICATE_KEY` (error) | second `put` overwrites the first (`:61`) | one entry's authZ and metadata vanish; users of that authZ lose the consent, with no log line |
| `authZ` is unique (after `\/` to `/`) | `FENCE_DUPLICATE_AUTHZ` (error) | second `put` overwrites (`:62`) | a FENCE login resolves to only one of the entries, so the user gets one consent instead of two |
| List the `public` entries | `FENCE_PUBLIC_ENTRIES` (info) | every user gets them (`BdcConsentsBuilder:49-53`) | an accidentally public study exposes its data to all logins; the list makes that visible |
| Partition coverage (split job only) | `FENCE_PARTITION_UNREACHABLE`, `FENCE_GLOBAL_NOT_GRANTED`, `FENCE_ENTRY_WITHOUT_PARTITION` (warnings) | n/a | a partition with no entry is loaded but nobody can query it; an entry with no partition grants nothing |

PSAMA offers no validation: there is no published schema (the "schema" is the `StudyMetaData` class),
no validate endpoint or CLI, and every parse error is swallowed by the `catch` at `:90-92`. Run
`validate-fence-mapping` before shipping a file.

## Deviations from the PSAMA classes, and why

Checked against the legacy generator (`ETLToolSuite-EntityGenerator`:
`etl/metadata/bdc/BDCMetadata.java`, `GenericBDCMetadata.java`, `BDCMetadataElements.java`), which
writes the production `general/data/metadata_new_search.json`:

- **Counts are `>= -1`, not `>= 0`.** `BDCMetadataElements` initialises all six counts to `-1`, and
  `GenericBDCMetadata` sets two of them to `-1` explicitly. A `>= 0` bound would reject every file the
  generator writes. PSAMA does not care about the value.
- **Strings the generator writes are never null** (they default to `""`), but an existing file that is
  re-read and re-written (`mapper.readValue(metadata, ...)`) can carry nulls through. The schema
  therefore allows `null` on optional strings, as PSAMA does.
- **`consent_group_code` is `"c" + key` from the data dictionary** (`BDCMetadata`), and `c0` is
  skipped. `GenericBDCMetadata` takes `GENERIC_CONSENT_CODE` verbatim, which may be empty. The
  generator does not constrain the key, so a dictionary value that is not numeric would produce a
  code outside `^(c[0-9]+)?$`. That is deliberate: such a code can never match a partition name, so
  the validator flags it. If the real file fails this rule, the entry is wrong or the partition
  naming needs to change; it is not a validator bug.
- **The schema is stricter than Jackson on counts.** Jackson coerces `"5"` and `5.7` to an `int`;
  the schema requires a true integer so the file stays unambiguous for other readers.
- The real production file was not available to this work, so the schema is checked against
  fixtures built from the generator's field list, not against `metadata_new_search.json` itself. Run
  `validate-fence-mapping` on the real file first.

## Proposal for the PSAMA team: a lean shape

Only four values drive behaviour; the other 19 are display metadata that belongs in the data
dictionary, and carrying them makes every unknown-field typo fatal. A proposed replacement:

```json
{
  "schema_version": 1,
  "consents": [
    { "study_id": "phs123456", "consent_code": "c1", "authz": "/programs/phs123456.c1", "public": false },
    { "study_id": "phs999999", "consent_code": null, "authz": "/programs/phs999999", "public": true }
  ]
}
```

- `consent_code` is nullable (study-level key); the key `study_id[.consent_code]` is derived, never
  stored, so it cannot disagree with the pair that produced it.
- `public` is a boolean instead of the string `study_type == "public"`.
- `authz` and the derived key are both unique; PSAMA should reject a duplicate at startup rather than
  overwrite.
- `schema_version` lets the format evolve without an unknown-field cliff.
- PSAMA should validate on load and refuse to start (or keep the previous mapping) on any error,
  instead of logging and continuing with an empty mapping.

This is a proposal only; nothing in this repo or in PSAMA implements it. The validator accepts the
legacy shape.
