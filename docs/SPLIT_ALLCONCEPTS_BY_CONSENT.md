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

The input is five columns, no header (one leading header row is tolerated and dropped):
`patient_num, concept_path, nval, tval, timestamp`, as in `schemas/allconcepts.tableschema.json`.
Both `\` and the legacy `µ` delimiters are accepted.

## What it does

1. **Pass 1** streams the input. Every row is checked against the CSVLoader contract (below), and for
   rows whose path equals `consents-path` the job records patient to set of `tval` values, for
   example `phs123456.c1`.
2. **Pass 2** takes the path segment at `depth` (`\phs123456\pht1\v\` at depth 1 is `phs123456`).
   - If that node is the study prefix of any consent string, the row is a **study row**. It is written
     to partition `<consent>` for each consent of that patient for that study (a patient with `c1` and
     `c2` of one study gets the row in both). A patient with no such consent is not written and is
     counted as `NO_CONSENT`.
   - Anything else (`_consents`, `_studies`, `DCC Harmonized data set`, ...) is a **global variable**.
3. Rows are spooled to one temp file per partition, uploaded only once the whole input is valid, then
   `split_manifest.json` is written last (its presence means the upload completed).

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
(exit 2, with the first five rows per code in the report). They mirror what
`CSVLoader` does and abort on:

- a leading header row is dropped when column 0 is not an integer (only for row 1); any later row
  needs an integer column 0 (`Integer.parseInt`);
- exactly 5 fields;
- `concept_path` non-empty;
- `nval` blank or a number (`Double.parseDouble` aborts otherwise; the check is the stricter Table
  Schema number);
- `timestamp` blank or `^-?[0-9]+$` and fits 64 bits (`Long.parseLong`).

Reported but not rejected: rows with timestamp `0` (info `TIMESTAMP_ZERO`) and positive timestamps
below 1e11 (warning `TIMESTAMP_LIKELY_SECONDS`: epoch seconds, not milliseconds).

Partition names must not contain `-` (the file suffix is `-allConcepts.csv`), a path separator, or be
`.`/`..`. A consent value that breaks this is an error; a global node has `-` replaced.

## Safety

- `output` containing `avillach-73-bdcatalyst-etl` (the legacy bucket or its in-boundary mirror
  prefix) is refused, also with `force`. In-boundary runners must not write there.
- A non-empty output prefix is refused unless `force=true`; with `force`, same-named files are
  overwritten. Nothing is ever deleted.
- The Jenkins job sets `force` on the retry after an infrastructure failure, since the first attempt
  may have uploaded some partitions.

## Manifest

`{output}/split_manifest.json`:

```json
{
  "input": { "uri": "..." }, "depth": 1, "consentsPath": "\\_consents\\", "globals": "own",
  "headerRowDropped": false,
  "rows": { "input": 9, "study": 4, "global": 5, "dropped": { "studyNoConsent": 0, "globalNoConsent": 0 },
            "accounted": 9, "balanced": true, "partitionLines": 10, "extraCopies": 1 },
  "timestamps": { "zero": 0, "likelySeconds": 0 },
  "partitions": [ { "name": "phs123456.c1", "kind": "study", "rows": 2, "patients": 2, "file": "phs123456.c1-allConcepts.csv" } ]
}
```

Rows are counted by **source row**: `input = study + global + dropped` must hold (`balanced`). Lines in
the partition files can exceed that because a row is repeated for a multi-consent patient and, in
`by-patient` mode, for each partition; `extraCopies = partitionLines - study - global` records that
separately. Per-partition `rows` is the number of lines in that file. S3 VersionId/ETag of the input
are not recorded because `IoResolver` does not expose them.

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

On a runner the job is started by `etl-runners/split-allconcepts-by-consent/Jenkinsfile`. The runner
needs a root disk of at least twice the input (`ROOT_VOLUME_GB`, default 1000; `by-patient` can need
more because it copies global rows into every partition); `preflight.sh` checks this against the
input size before provisioning. The runner reads the in-boundary bucket with the instance role;
`CONTAINER_ASSUME_ROLE_ARN` stays blank.

Exit codes follow the repo contract: 0 success (warnings included), 2 input violates the CSVLoader
contract, the output location is refused, or the row accounting does not balance, 3 data, 4
infrastructure (retried once by Jenkins), 5 config.
