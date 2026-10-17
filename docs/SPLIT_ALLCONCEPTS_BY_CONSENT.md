# split-allconcepts-by-consent

Splits the one merged allConcepts CSV (every study plus the global variables) into one file per
consent partition for the partitioned HPDS loader. No database, no patient-id mapping: patient
numbers stay as they are in the merged file.

- Job: `SplitMergedAllConceptsJob` (`--job=split-allconcepts-by-consent`)
- Runner: `etl-runners/split-allconcepts-by-consent/` (Jenkinsfile, preflight, validate, terraform, `test/run-tests.sh`)
- Schemas: `schemas/allconcepts.tableschema.json`, `schemas/fence_mapping.schema.json`
- Not to be confused with `split-allconcepts`, the temporary per-study migration job that maps ids through the database.

## Inputs

| Param | Default | Meaning |
|---|---|---|
| `input` (required) | | merged allConcepts CSV, local path or `s3://`. Production: `s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_merged/allConcepts_data_analyzer.csv` |
| `output` (required) | | output prefix. Production: `s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_split/<run id>/` |
| `depth` | `1` | 0-based path segment that names the study; the leading empty segment is depth 0 |
| `consents-path` | `\_consents\` | path of the variable whose `tval` holds each patient's consent strings |
| `globals` | `own` | `own` or `by-patient` (below) |
| `fence-mapping` | the mirrored legacy `metadata_new_search.json` | report-only coverage cross-check; `none` skips it |
| `force` | `false` | allow a non-empty output prefix |
| `timestamps` | `strict` | `strict` or `convert-iso` (see Repairs) |
| `zero-timestamps` | `keep` | `keep` or `blank` (see Repairs) |
| `bad-rows` | `fail` | `fail` or `quarantine` (see Repairs) |
| `max-reject-fraction` | `0.01` | decimal in [0,1]: largest share of data rows `quarantine` may set aside |

The input is five columns, no header (one leading header row is tolerated and dropped):
`patient_num, concept_path, nval, tval, timestamp`, as in `schemas/allconcepts.tableschema.json`.
Both `\` and the legacy `µ` delimiters are accepted.

## What it does

0. **Pin the input.** The job reads the input's identity first (S3 `versionId` and `eTag`; for a local
   file, size and mtime) and opens both passes against exactly that object: `GetObject` with
   `If-Match: eTag` (needs only `s3:GetObject`; `versionId` is recorded for provenance but not used,
   since a versioned read would need `s3:GetObjectVersion`). After each pass it checks the
   identity again. If the object was replaced in between, the run fails with `INFRASTRUCTURE_ERROR`
   (exit 4, "Input changed since it was first read") before anything is uploaded.
1. **Pass 1** streams the input. Every row is checked against the CSVLoader contract (below), and for
   rows whose path equals `consents-path` the job records patient to set of `tval` values, for
   example `phs123456.c1`.
2. **Pass 2** takes the path segment at `depth` (`\phs123456\pht1\v\` at depth 1 is `phs123456`).
   - If that node is the study prefix of any consent string, the row is a **study row**. It is written
     to partition `<consent>` for each consent of that patient for that study (a patient with `c1` and
     `c2` of one study gets the row in both). A patient with no such consent is not written and is
     counted as `NO_CONSENT`.
   - Anything else (`_consents`, `_studies`, `DCC Harmonized data set`, ...) is a **global variable**.
3. Rows are spooled to one temp file per partition (and one for rejects), uploaded only once the whole
   input is valid, then `split_manifest.json` is written last (its presence means the upload completed).

Rows are unchanged in content and keep their input order. Output files are headerless, every field
quoted (`"` doubled), LF line ends, UTF-8.

### Globals modes

| `globals` | Where a global row goes | Output | Auth today |
|---|---|---|---|
| `own` (default) | its own partition named after the node, `-` replaced by `_` (`pheno-sub` becomes `pheno_sub`). An empty node is an error. | one file per global node, plus the study partitions | **unreachable until PSAMA grants those exact names** |
| `by-patient` | copied into every partition of that patient (all their consent strings). A patient with no consents is dropped and counted. | study partitions only, larger | works with today's auth |

**Auth caveat for `own`.** HPDS reads only partition directories whose name equals one of the user's
consent strings from PSAMA (`PartitionedPhenotypicObservationStore`, unknown names skipped
silently). PSAMA emits only fence-mapping keys: `phsX.cY`, a bare study id, and the `public` entries
(`BdcConsentsBuilder:35-60`, see `docs/FENCE_MAPPING.md`). The `_harmonized_consent` and
`_topmed_consents` constants are unused. So `own` partitions load fine, but no user can query them
until PSAMA grants those names, and a `public` entry for one would expose every patient's global
rows to everyone. `own` is the default as chosen; use `by-patient` if the globals must be queryable
now. The cross-check reports each global partition as `FENCE_GLOBAL_NOT_GRANTED`.

At depths of 2 or more the `_consents` row itself has no segment at that depth, so `own` mode
fails with `EMPTY_GLOBAL_NAME`; use `by-patient` there.

## CSVLoader checks

Run on every row before anything is written; any violation is an error and **nothing is uploaded**
(exit 2, with the first five rows per code in the report), unless the Repairs below are enabled. They mirror what
`CSVLoader` does and abort on:

- a leading header row is dropped when column 0 is not an integer (only for row 1); any later row
  needs an integer column 0 (`Integer.parseInt`);
- exactly 5 fields;
- `concept_path` non-empty;
- `nval` blank or accepted by `Double.parseDouble(nval.trim())`, exactly CSVLoader's test (so padded
  values, `NaN` and `Infinity` pass; `1,5` fails). The Table Schema type `number` is a little
  stricter; the Java check follows what CSVLoader will actually accept;
- `timestamp` blank or `^-?[0-9]+$` and fits 64 bits (`Long.parseLong`).

Reported but not rejected: rows with timestamp `0` (info `TIMESTAMP_ZERO`; `zero-timestamps=blank` empties
them, see Repairs) and positive timestamps below 1e11 (warning `TIMESTAMP_LIKELY_SECONDS`: epoch seconds,
not milliseconds).

Partition names must not contain `-` (the file suffix is `-allConcepts.csv`), a path separator, or be
`.`/`..`. A consent value that breaks this is an error (`INVALID_CONSENT_VALUE`); a global node has `-`
replaced. With the defaults all of the above stop the run. `bad-rows=quarantine` and
`timestamps=convert-iso` make the job clean the data instead, and `zero-timestamps=blank` empties timestamps of 0; all are opt-in, logged and counted.

## Repairs (opt-in)

The first real run over the legacy merged file (about 2.35 billion rows) stopped with `NOTHING_WRITTEN`:
header rows in the middle of the file (`patient_num` = `subject_id`), about 2.3 million ISO-8601
timestamps, one `nval` of `2402L`, and consents such as `tutorial-<study>_<name>` that contain `-`. The
validation was right, CSVLoader aborts on every one of them. Nothing disappears silently: every repair is
counted in the manifest and the report, and the defaults stay strict.

The same run also found that 2,627,228,645 of 2,629,578,924 rows (99.9%) have timestamp `0`. CSVLoader turns
`0` into `new Date(0)`, 1970-01-01, so nearly every observation would be dated 1970; the hpds-ingest
ingest-service treats `0` as no timestamp.

**Recommended for the legacy merged file:** `--timestamps=convert-iso --bad-rows=quarantine --zero-timestamps=blank`.

| Param | Values | Effect |
|---|---|---|
| `timestamps` | `strict` (default) | a timestamp that is not blank or an integer is `BAD_TIMESTAMP` |
| | `convert-iso` | a timestamp that is not an integer is converted if it has one of the forms below, using the same rules as the hpds-ingest ingest-service (`TimestampParser.parse`), and is rewritten to epoch milliseconds in the output line (`1686064800000`) and counted. Anything else stays `BAD_TIMESTAMP`. Sub-millisecond digits are truncated. |
| `zero-timestamps` | `keep` (default) | a timestamp of `0` is written as is (and loads as 1970-01-01) |
| | `blank` | a timestamp that is the integer 0 is written as an empty field, which CSVLoader loads as no timestamp. See below. |
| `bad-rows` | `fail` (default) | any row that breaks the contract, or any consent that cannot name a partition, is an error: exit 2, nothing written |
| | `quarantine` | such a row is not written to any partition; it goes to `rejects/rejected-rows.csv` and the run continues |
| `max-reject-fraction` | `0.01` | with `quarantine`: if rejected rows / data rows is **greater** than this, the run fails as under `fail` (exit 2, `REJECT_FRACTION_EXCEEDED`, nothing written, the message names the counts and the reasons), so a broken input cannot be quietly halved. Invalid values (not a decimal, outside [0,1], NaN) are `INVALID_MAX_REJECT_FRACTION`. |

Invalid values of the other three are `INVALID_TIMESTAMPS`, `INVALID_ZERO_TIMESTAMPS` and `INVALID_BAD_ROWS`.

`zero-timestamps=blank` in detail:

- It decides on the **parsed** value, after the integer check: `0`, `00` and `-0` are all blanked, any other integer (`1`, `-5`) and an already blank field are untouched. The text is replaced by an empty field; nothing else in the row changes.
- It is independent of `timestamps` and `bad-rows`: it works in `strict` mode (where integers are the only timestamps) and with `convert-iso`. With `convert-iso`, the blanking is applied **after** the conversion, so an ISO value that converts to epoch 0 (`1970-01-01T00:00:00Z`, `1970-01-01`, `1970-01-01T01:00:00+01:00`) is blanked as well. Such a value counts both as converted (in `timestampsConverted`, by form) and as blanked. Without `blank` it would be written as `0`.
- A blanked field is counted once per source row that passed the contract check, in both passes, whatever happens to the row afterwards: a row that is then dropped for lack of a consent, or copied into several partitions, still counts once. A row that fails the check is never blanked, so `rejects/rejected-rows.csv` holds it as read. A row that passed and is then set aside only because its consent cannot name a partition (`INVALID_CONSENT_VALUE`) is filed with its final line, i.e. with the timestamp blanked (or converted), like a converted one.
- `timestampZero` (the zeros seen, `timestamps.zero` in the manifest) is unchanged: it still counts the rows that had timestamp 0, blanked or not.
- Blanking is a note (info `TIMESTAMPS_BLANKED`), not a warning, because on the legacy merged file it applies to almost every row.

Forms `convert-iso` accepts, tried in this order (the manifest and report count them as `timestampsConvertedByForm`):

| Form (key) | Accepted text | Read as |
|---|---|---|
| `offset` | ISO-8601 instant with `Z` or an offset: `2023-06-06T15:20:00Z`, `2023-06-06T15:20:00+00:00`, `2023-06-06T17:20:00+02:00`, `2023-06-06T15:20:00.123Z` | the instant as given |
| `zoneless-utc` | `uuuu-MM-dd HH:mm:ss` with an optional fraction of 1-9 digits (`2023-11-25 14:00:00`, `2020-01-02 03:04:05.678`, `2021-06-07 08:09:10.9`), or an ISO local date-time (`2023-06-06T15:20:00`, `2025-02-28T12:56:40.500`) | **UTC (assumed)** |
| `date-utc` | ISO local date (`2023-06-06`) | **midnight UTC (assumed)** |

Integers, `0` included, are never converted: they are CSVLoader's own contract (`zero-timestamps=blank` empties a `0`). Not accepted, so still
`BAD_TIMESTAMP`: an offset without a colon (`2023-06-06T15:20:00+0000`), a space-separated value with an
offset, an invalid date or time (`2023-13-06T15:20:00Z`, `2023-13-45 99:00:00`), a time without seconds,
and any other text. The zoneless forms carry no zone, so their meaning is an assumption: they are read as
UTC because that is what the ingest-service does with the same strings, which keeps the two paths
consistent. If the source system wrote local time, those instants are shifted; check that before relying
on them. The run says so with the `TIMESTAMPS_ASSUMED_UTC` warning whenever `zoneless-utc` or `date-utc`
is above 0.

What `quarantine` sets aside, by `reason`:

- `BAD_FIELD_COUNT`, `BAD_PATIENT_NUM`, `EMPTY_CONCEPT_PATH`, `BAD_NVAL`, `BAD_TIMESTAMP`: the checks above
  (after any ISO conversion). A row failing several checks is filed under the first, in the order listed
  in CSVLoader checks.
- `EMBEDDED_HEADER`: a header row that is not row 1 (row 1 is still dropped and not counted). It is a row
  of five fields whose first column is not an integer (the same test as the leading header) and none of
  whose fields holds a concept path (no `\` or `µ`); a row with a bad patient number but a real path is
  `BAD_PATIENT_NUM`, not a header.
- `INVALID_CONSENT_VALUE`: a consent value that cannot name a partition (here `tutorial-demo_one`) is
  **dropped for every patient**; it is not renamed, because HPDS only reads partitions named exactly like a
  granted consent. A study or by-patient row whose only consents for that study are dropped ones goes to
  rejects. A row that still has another valid consent is written there and only loses the copy for the
  dropped one. The dropped consent's own row in `_consents` is a global row and is kept.

Both passes decide each row with the same check (`SplitRowCheck`), so a row rejected in pass 1 is rejected in
pass 2 and never written. The job verifies this: if the two passes disagree, the run fails with
`PASS_MISMATCH` and nothing is uploaded. Row numbers are unchanged: the 1-based count of non-blank rows,
a leading header counting as row 1.

### Rejects file

`{output}/rejects/rejected-rows.csv`, written only when at least one row was rejected. Headerless, every
field quoted (`"` doubled), LF line ends, UTF-8, in input order, uploaded with the partitions. It never ends in
`-allConcepts.csv`, so no loader picks it up. Seven columns:

| Column | Content |
|---|---|
| 1 `reason` | one of the reasons above |
| 2 `row` | row number in the input (the number used in error messages) |
| 3-7 | the input row: `patient_num, concept_path, nval, tval, timestamp`. A row with the wrong field count keeps its own fields, so it has fewer or more columns. For `INVALID_CONSENT_VALUE` the timestamp is the converted one, as it would have been written. |

```
"BAD_NVAL","2347627436","7","\phs000000\pht0\v\","2402L","","1700000000000"
```

### Reporting

Repairs are warnings, never errors, so a run that used them exits 0 with warnings (UNSTABLE in Jenkins). The one
exception is the blanking of zero timestamps, an info note `TIMESTAMPS_BLANKED` with the count, which does not
make the run UNSTABLE by itself. The warnings are:
`TIMESTAMPS_CONVERTED` (with the count per form), `TIMESTAMPS_ASSUMED_UTC` (zoneless or date-only values were
read as UTC), `ROWS_QUARANTINED` (counts per reason and the rejects file), `CONSENTS_DROPPED`
(each value with its row count). The report metrics gain `timestampsMode`, `zeroTimestampsMode`, `badRowsMode`,
`timestampsConverted`, `timestampsConvertedByForm` (`offset`, `zoneless-utc`, `date-utc`), `timestampsBlanked`, `rejectedRows`, `rejectedByReason`, `droppedConsents`, `rejectsUri`.

## Safety

- `output` containing `avillach-73-bdcatalyst-etl` (the legacy bucket or its in-boundary mirror
  prefix) is refused, also with `force`. In-boundary runners must not write there.
- A non-empty output prefix is refused unless `force=true`; with `force`, same-named files are
  overwritten. Nothing is ever deleted.
- The Jenkins job sets `force` on the retry after an infrastructure failure, since the first attempt
  may have uploaded some partitions. The retry does not compare itself with a manifest a partial
  attempt may have left (the manifest is written last, so an attempt that died earlier leaves none);
  pinning means each attempt is internally consistent, and a retry that finds a replaced input fails
  rather than mixing versions. Compare `input.versionId`/`input.eTag` in the final manifest with the
  object you expect.

## Manifest

`{output}/split_manifest.json`:

```json
{
  "input": { "uri": "..." }, "depth": 1, "consentsPath": "\\_consents\\", "globals": "own",
  "headerRowDropped": false,
  "rows": { "input": 9, "study": 4, "global": 5, "dropped": { "studyNoConsent": 0, "globalNoConsent": 0 },
            "rejected": 0, "accounted": 9, "balanced": true, "partitionLines": 10, "extraCopies": 1 },
  "timestamps": { "zero": 0, "likelySeconds": 0 },
  "repairs": {
    "timestamps": "strict", "zeroTimestamps": "keep", "badRows": "fail", "maxRejectFraction": 0.01, "timestampsConverted": 0,
    "timestampsConvertedByForm": { "offset": 0, "zoneless-utc": 0, "date-utc": 0 }, "timestampsBlanked": 0,
    "rejected": { "total": 0, "byReason": {} },
    "droppedConsents": [],
    "rejectsFile": null
  },
  "partitions": [ { "name": "phs123456.c1", "kind": "study", "rows": 2, "patients": 2, "file": "phs123456.c1-allConcepts.csv" } ]
}
```

Rows are counted by **source row**: `input = study + global + dropped + rejected` must hold (`balanced`;
`rejected` is `repairs.rejected.total` and is 0 unless `bad-rows=quarantine`). Lines in
the partition files can exceed that because a row is repeated for a multi-consent patient and, in
`by-patient` mode, for each partition; `extraCopies = partitionLines - study - global` records that
separately. Per-partition `rows` is the number of lines in that file. `input` records the pinned
object: `uri`, `versionId` (null on a never-versioned bucket), `eTag`, `size`, `lastModifiedMillis`
(`versionId` and `eTag` are null for a local file). `studyNodesWithoutConsents` lists the nodes below.

`repairs` records what the opt-in repairs did: the three modes and the limit, `timestampsConverted` (the total) and
`timestampsConvertedByForm` (its split into `offset`, `zoneless-utc` and `date-utc`; the two passes must
agree on it, else `PASS_MISMATCH`), `timestampsBlanked` (the number of zero timestamps written empty; the passes must agree on it too),
`rejected.byReason` (the `INVALID_CONSENT_VALUE` rows included), `droppedConsents` as
`{"value", "problem", "rows"}` (`rows` counts the row copies that were meant for that consent and were not
written, whether or not another partition still got the row), and `rejectsFile` (the URI, or null when
nothing was rejected).

`STUDY_NODE_WITHOUT_CONSENTS` (warning, both globals modes): a `phsNNNNNN` node at `depth` that is
not a study in `consents-path` is handled as a global variable (own partition, or copied by patient),
and listed in the report and the manifest. It usually means a study whose consents are missing.

## fence_mapping cross-check (report-only)

Unless `fence-mapping=none`, the job validates the fence mapping with `FenceMappingValidator` and
compares it with the partitions written. It never changes the exit code beyond a warning:

- fence mapping errors are downgraded to warnings;
- `FENCE_PARTITION_UNREACHABLE`: study partitions with no entry (no one is granted them);
- `FENCE_GLOBAL_NOT_GRANTED`: global partitions with no entry;
- `FENCE_ENTRY_WITHOUT_PARTITION`: entries with no partition in this split;
- `FENCE_MAPPING_UNREADABLE`: the file could not be read.

## Running

```bash
./mvnw -q package -DskipTests
java -jar target/hpds-etl.jar --job=split-allconcepts-by-consent \
  --input=./merged.csv --output=./split-out --fence-mapping=none

etl-runners/split-allconcepts-by-consent/test/run-tests.sh     # preflight/validate shell tests
```

`etl-runners/split-allconcepts-by-consent/validate.sh` checks the fetched report after a run. The row
accounting it verifies is `input == study + global + dropped + rejected` (it reads `metrics.rejectedRows`,
0 when absent), and it warns (exit 10, `no rows quarantined`) when any row was set aside, next to the
existing warnings for dropped rows and epoch-second-looking timestamps.

On a runner the job is started by `etl-runners/split-allconcepts-by-consent/Jenkinsfile`. The runner
needs a root disk of at least twice the input (`ROOT_VOLUME_GB`, default 1000; `by-patient` can need
more because it copies global rows into every partition); `preflight.sh` checks this against the
input size before provisioning. The runner reads the in-boundary bucket with the instance role;
`CONTAINER_ASSUME_ROLE_ARN` stays blank.

Exit codes follow the repo contract: 0 success (warnings included), 2 input violates the CSVLoader
contract, `max-reject-fraction` is exceeded, the output location is refused, or the row accounting does not balance, 3 data, 4
infrastructure (retried once by Jenkins), 5 config.
