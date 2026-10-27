# adapt-allconcepts-csvloader

**Temporary.** A bridge between the per-study allConcepts layout that `generate-allconcepts-manifest` describes and the input the
in-boundary runner `hpds-pheno-load` (flow `csvloader-split`) and the stock pic-sure `CSVLoader` accept. It reads the files, checks
every row, routes the global rows into the consent partitions and writes the flat split layout the runner stages. No database.

Contents: [Purpose and removal](#purpose-and-removal) | [Inputs](#inputs) | [Outputs](#outputs) | [Routing](#routing-of-the-global-file) |
[Row rules and reject codes](#row-rules-and-reject-codes) | [Failures and exit codes](#failures-and-exit-codes) |
[Memory and disk](#memory-and-disk) | [Local proof of concept](#local-proof-of-concept-v1-vector-only) | [Running it](#running-it)

## Purpose and removal

The per-study pipeline writes one `{study}_c{code}_allConcepts_MERGED.csv` per consent group plus `global_AllConcepts.csv`, pinned by
S3 version id and MD5 in `allConcepts_manifest.csv`. The runner expects `<partition>-allConcepts.csv` files with every global row
already placed in the partitions of the patients it belongs to, and a `split_manifest.json` (it was written for the splitter of
`split-by-consent`). This job produces exactly that, so neither the runner nor CSVLoader changes.

Delete the job (`jobs/adapter/`, its tests and vectors, `etl-runners/adapt-allconcepts-csvloader/`, the `application.yml` flag) when
**any** of these holds:

- the per-study pipeline writes the runner's split layout itself;
- the runner reads the manifest layout directly (the `picsureloader-combined` flow or a manifest-aware staging step);
- the PICSURELoader replaces CSVLoader for this load and no longer needs the split input.

Its job type is `MIGRATION` ("temporary, delete when no longer needed") although it lives in `jobs/adapter/`, and it has no stage in
either orchestrator: it is started by hand from its own Jenkins job.

## Inputs

`--k=v` parameters (any other `--k=v` is passed on by `JobLauncher` and ignored by this job, so check the spelling):

| parameter | default | meaning |
|---|---|---|
| `--manifest=` | required | `allConcepts_manifest.csv`: `s3://` of a versioned bucket (a local path works for the proof of concept) |
| `--output=` | required | prefix or directory for the result; must be **empty** and must not contain `avillach-73-bdcatalyst-etl` |
| `--studies=` | all | comma list of study ids to adapt; unknown ids are refused |
| `--max-reject-fraction=` | `0` | largest share of all input rows that may be rejected |
| `--allow-consent-codes=` | none | consent codes accepted although they are not positive integers (`0`, `public`) |
| `--multi-study-ids=` | `copy` | `copy`: ids of patients in several studies go to every partition they hold when they are patient-level; `refuse`: any such patient stops the run |
| `--zero-timestamps=` | `blank` | the only accepted value; it is a parameter so the choice shows in the logs |
| `--work-dir=` | system temp | where the output is built before upload; needs room for the whole output |

The manifest is read as `GenerateAllConceptsManifestJob` writes it: header `partition,s3_uri,version_id,last_updated,md5`, row `global`
(the global file) and one row `{study}.c{code}` per consent group. Columns after the fifth are ignored. A relative local `s3_uri` is
taken from the manifest's directory.

Every file is fetched **by its manifest `version_id`** (a versioned `GetObject`, not the current object) and its MD5 is computed
while streaming; a mismatch with the manifest's `md5` ends the run (exit 3) naming the partition. The manifest's own current version
id is read first and again at the end; if it moved, the run fails (exit 3).

### What is refused before any data file is read (exit 2)

- the output contains `avillach-73-bdcatalyst-etl` (the legacy bucket and its in-boundary mirror), or holds any object/file;
- a bad parameter value;
- no `global` row; a duplicate partition (case-insensitive); an `s3://` row without a version id; a row without a 32-digit md5;
- a partition name the runner would refuse (`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`, no `..`), one that contains `-`
  (`HYPHENATED_PARTITION`), or one that is not `{study}.c{code}` with the code matching `[A-Za-z0-9_]+`;
- a consent code that is not a positive integer (`^[1-9][0-9]*$`), such as `0` or `public`, unless listed in
  `--allow-consent-codes`. All codes seen are in the report (`consentCodesSeen`) either way.

## Outputs

Under `--output`, uploaded only after every check has passed, `split_manifest.json` last (a prefix that holds it is complete):

| object | content |
|---|---|
| `<partition>-allConcepts.csv` | `{study}.c{code}`; no header; five fields per record, **every field double-quoted** (`"` doubled), LF, UTF-8; **not sorted** (the runner sorts by column 2); timestamp `""` when 0 |
| `split_manifest.json` | the runner's contract: `partitions[] = {name, kind: "study", file, rows, patients}` plus `job`, `runId`, `generatedAt`, `input {uri, versionId, md5, rowCount}`, `rows {input, globalCopies, written, rejected, dropped, balanced}`, `rejects`, `multiStudyPatients`, `consentCodesSeen`, `warnings` |
| `report.json` | the same numbers with the detail: dropped by reason, per-partition counts, the first message per reject code, timestamp conversions |
| `rejects/<partition>.csv`, `rejects/global.csv` | only when non-empty: `"CODE",<record number>,"<original record>"`, capped at 100 000 lines per file (the counts are complete) |

`rowCount` in `input` is the number of rows of the manifest; the data rows read are `rows.input`.

The runner's gates it satisfies (checked by `etl-runners/adapt-allconcepts-csvloader/test/run-tests.sh` against the runner's own
scripts): a non-empty `partitions[]`; `name` matching the regex with no `..`; `kind` `study`, never `global`; `file` =
`<name>-allConcepts.csv`; `rows` a whole number above 0; in the container, the file's CSV record count equals `rows` and so does its
count of non-empty lines (hence no field may hold a line break).

A failed run leaves `--output` **untouched** (so it can be retried at once). Its `report.json` and rejects are in the reports
directory of the run (`adapt-allconcepts-csvloader-<runId>-report.json`, `...-rejects/`), which Jenkins archives; a successful run
writes the same report there too. The JobResult is `adapt-allconcepts-csvloader-<runId>.json`.

## Routing of the global file

The patient index is built first from the `_consents` rows (a first pass over the global file); then the global file is read again and
each row is routed; then each partition file is read. D1 is *study-scoped* (a study's rows follow the patient's consent of that
study); D2 is *copy* (an id row goes to every partition the patient holds).

| global row | goes to | when it does not |
|---|---|---|
| `_consents` with text `S.cN`, empty number | partition `S.cN` only (D1) | text not a partition of the manifest: reject `CONSENT_NOT_IN_MANIFEST`; a number in the numeric column: reject `NUMERIC_NVAL`; a partition left out by `--studies`: dropped |
| `_studies_consents\S\...` (any depth below `S`) | every partition of study `S` the patient holds (D1) | study not in the manifest: reject `CONSENT_NOT_IN_MANIFEST`; study left out: dropped; the patient holds no partition of `S`: dropped (`studyNotHeld`) |
| `_source_subject_id`, `_source_sample_id` | every partition the patient holds, one row each, after de-duplication (D2) | see below |
| any other path | rejected `UNKNOWN_GLOBAL_PATH` (a case variant of a reserved path: `NON_CANONICAL_PATH`) | |
| any global row of a patient with no `_consents` row in scope | dropped, counted (`globalNoConsent`) | |

Id rows and patients in several studies: the global file repeats a participant's ids once per study they are in, with no study
attribution. For a patient in *k* studies each distinct id row (same path, values and timestamp) must therefore occur **exactly k
times** (more is a duplicate, merged and counted). If any occurs fewer than *k* times the ids are study-specific, and copying them to
every partition would put an id in a study it does not belong to: with `--multi-study-ids=copy` the run fails (exit 3,
`ID_ROWS_NOT_PATIENT_LEVEL`, naming up to ten patient ids and paths, never values); with `refuse` any patient in two or more studies
fails it (`MULTI_STUDY_PATIENTS`). *k* counts the studies of the whole manifest, so a patient in a study that `--studies` left out is
still multi-study and its id rows are still merged. An id row of a patient in a single study is copied as read and **not**
de-duplicated (that would need a memory entry per row; the generator writes each once).

A data row in a partition file is accepted only if its first path node equals the partition's study (else `STUDY_PARTITION_MISMATCH`)
and its patient has a `_consents` row for exactly that partition (else `DATA_ROW_WITHOUT_CONSENT`).

## Row rules and reject codes

`AdapterRowCheck` (modelled on `SplitRowCheck` of `split-by-consent`; nothing is imported from it) takes the first check that fails.
A row is never silently changed: the only edits are the timestamp rules below and re-quoting. Paths are written exactly as read
(`µ` or `\`; CSVLoader turns `µ` into `\`).

| code | check |
|---|---|
| `MALFORMED_RECORD` | not exactly 5 fields, or a line that is not one CSV record (unterminated quote, text after a closing quote) |
| `EMBEDDED_LINE_BREAK` | a CR or LF inside a field (a quoted field spanning lines) |
| `BAD_PATIENT_ID` | patient id not an integer in [0, 2147483647] |
| `NON_CANONICAL_PATH` | path not wrapped (starts and ends with the same delimiter `µ` or `\`); mixed delimiters; an empty segment; a `.` or `..` segment; a control character; first node with a leading/trailing character up to U+0020 or U+FFFD (ADR-0014 R1/R2) |
| `NUMERIC_NVAL` | numeric column neither empty nor a finite decimal (`nan`, `inf`, `12L`, whitespace) |
| `BAD_TIMESTAMP` | not empty, an integer, or an ISO-8601 date/date-time |
| `STUDY_PARTITION_MISMATCH`, `DATA_ROW_WITHOUT_CONSENT` | partition files, see above |
| `CONSENT_NOT_IN_MANIFEST`, `UNKNOWN_GLOBAL_PATH` | global file, see the routing table |

Timestamps: empty or `0` is written empty; another integer is kept; an ISO instant with `Z` or an offset, a zoneless date-time
(`uuuu-MM-dd HH:mm:ss[.fraction]` or ISO `T`, read as UTC) or a bare date (midnight UTC) becomes epoch milliseconds (0 after
conversion is written empty too). A **header line** (the first record of a file whose first field is `patient_num` or `hpds_id`, any
case) is dropped and counted, not rejected; blank lines are dropped and counted. The file must be valid UTF-8 (exit 3 otherwise).

Accounting: every record read ends as written, rejected or dropped, and a global row routed to *n* partitions counts *n−1*
`globalCopies`, so `rows.input + rows.globalCopies = rows.written + rows.rejected + rows.dropped` for each file and for the run;
`rows.balanced` must be true.

## Failures and exit codes

| exit | cases |
|---|---|
| 0 | done; notes such as dropped headers or rejects within the allowed fraction are warnings in the report (`SUCCESS_WITH_WARNINGS`, still exit 0, which `validate.sh` turns into an UNSTABLE build) |
| 2 | the refusals listed under Inputs |
| 3 | `MD5_MISMATCH`; `MANIFEST_CHANGED`; `ID_ROWS_NOT_PATIENT_LEVEL`; `MULTI_STUDY_PATIENTS`; `INVALID_UTF8`; `REJECT_FRACTION_EXCEEDED` (more rejects than `--max-reject-fraction` of the input rows); `EMPTY_PARTITION` (a partition ends with 0 rows); `ROW_ACCOUNTING_UNBALANCED` |
| 4 | S3 or disk failure (Jenkins retries once) |
| 5 | an input does not exist (manifest, a pinned version) |

Rejected rows are kept out of the data and listed in `rejects/`; set `--max-reject-fraction` deliberately, and only after reading the
report of a run at `0`.

## Memory and disk

Files are streamed (a record at a time), never loaded. What is kept: the patient-to-consents index (about 100 bytes per patient that
holds a consent), the id rows of patients in more than one study, one `BitSet` per partition for the patient counts (up to
highest-id/8 bytes each; HPDS ids are small sequence numbers), and counters. At most 64 output files are open at once. The output is
built in `--work-dir`, so the disk needs room for the **whole output**, which is about the size of the partition files plus the
global rows copied into them (the runner's root volume is `ROOT_VOLUME_GB`, default 500). The global file is read twice and each
partition file once, each time with its MD5 checked.

## Local proof of concept (v1 vector only)

On the committed synthetic vector only. **Real data never runs on a laptop.**

```bash
cd pic-sure-hpds-etl && ./mvnw -DskipTests package
V=src/test/resources/adapter-vectors/v1
java -jar target/hpds-etl.jar --job=adapt-allconcepts-csvloader --manifest=$V/inputs/allConcepts_manifest.csv --output=/tmp/adapted-v1

# CSVLoader wants each file sorted by concept path (column 2), as the runner does
for f in /tmp/adapted-v1/*-allConcepts.csv; do LC_ALL=C sort -t, -k2 "$f" -o "$f"; done

# CSVLoader image from the pic-sure feature-harmonization branch (services/pic-sure-hpds/docker/pic-sure-hpds-etl/Dockerfile);
# it walks the mounted directory for *allConcepts.csv and writes one directory per partition next to each file.
# The directory needs an `encryption_key` file (a test-only 32-character key for this vector).
docker run --rm -v /tmp/adapted-v1:/opt/local/hpds -e LOADER_NAME=CSVLoader -e LOADER_ARGS=/opt/local/hpds <hpds-etl image>
```

The `docker run` line is the documented entrypoint contract of that image; it is not exercised by this repository's tests. The
runner-side gates are, by `etl-runners/adapt-allconcepts-csvloader/test/run-tests.sh` (`make -C etl-runners/adapt-allconcepts-csvloader test`).

## Running it

Jenkins job `adapt-allconcepts-csvloader` (`etl-runners/adapt-allconcepts-csvloader/Jenkinsfile`): `MANIFEST` and `OUTPUT` are required;
pre-flight refuses an output under the legacy bucket prefix or not empty, and probes `ListBucketVersions` / `GetObjectVersion` on the
manifest's bucket once. Start it by hand; it is in no orchestrator. Then load with the in-boundary runner `hpds-pheno-load`:

```
FLOWS=csvloader-split PIC_SURE_REF=feature-harmonization SPLIT_URI=<OUTPUT>
```

The runner stages `<OUTPUT>split_manifest.json` and the files it lists, so `OUTPUT` needs its trailing slash (the runner adds one).
