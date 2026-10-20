# Permanent Ingestion Pipeline

The ongoing production pipeline that loads biomedical study data into HPDS. It populates
the participant database tables (`participants`, `consents`, `samples`), generates the
allConcepts CSVs that HPDS ingests, creates VCF indexes for genomic studies, and merges
per-consent allConcepts files.

Orchestrated by [`/Jenkinsfile`](../Jenkinsfile) (Jenkins job `hpds-etl-pipeline` in the
`hpds-etl` folder). All jobs have `JobType.PERMANENT`.

## Table of Contents

- [Pipeline DAG](#pipeline-dag)
- [Trigger Modes](#trigger-modes)
- [Pre-flight Only](#pre-flight-only)
- [Stage 1: Build and Test](#stage-1-build-and-test)
- [Stage 2: Resolve Studies](#stage-2-resolve-studies)
- [Stage 3: Package Runner Image](#stage-3-package-runner-image)
- [Stage 4: Start Participant DB](#stage-4-start-participant-db)
- [Stage 5: Load SSTR Participants](#stage-5-load-sstr-participants)
- [Stage 6: Generate Global AllConcepts](#stage-6-generate-global-allconcepts)
- [Stage 7: Create VCF Indexes](#stage-7-create-vcf-indexes)
- [Stage 8: Generate Per-Study AllConcepts](#stage-8-generate-per-study-allconcepts)
- [Stage 9: Merge AllConcepts](#stage-9-merge-allconcepts)
- [Stage 10: AllConcepts Manifest](#stage-10-allconcepts-manifest)
- [Stage 11: QA Consent Counts](#stage-11-qa-consent-counts)
- [Post: Back Up Participant DB](#post-back-up-participant-db)
- [End-of-run Report](#end-of-run-report)
- [Timeouts](#timeouts)
- [Merged-file Lifecycle (S3 Versions)](#merged-file-lifecycle-s3-versions)
- [Data Flow Summary](#data-flow-summary)
- [Parameters](#parameters)
- [Exit Codes](#exit-codes)
- [Future Improvements](#future-improvements)

---

## Pipeline DAG

```
Build ▸ Tests ▸ Resolve studies   probe every study's inputs (in parallel); a sweep EXCLUDES a
                      │           study missing one (UNSTABLE) instead of failing
                      ▼
          ┌───────────────────────┐
          │  Package runner image │  one image for every runner build below (PREBUILT_IMAGE_TAR)
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Start participant DB │  participant-db-start: make sure the long-lived DB is up
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Load SSTR participants│  (per unprocessed study; sequential by default)
          │  sstr-populate-rds-   │
          │  participants         │
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Generate global      │  (all ready studies, one run)
          │  AllConcepts          │
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Create VCF indexes   │  (genomic studies only, one run)
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Generate per-study   │  (per unprocessed study; STUDY_CONCURRENCY at once, largest
          │  AllConcepts          │  first) any failure halts here, before the merge
          │  all-concepts-data-   │
          │  generator            │
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Merge AllConcepts    │  (consent folders of ready studies whose sources changed)
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  AllConcepts manifest │  verifies every ready consent group's MERGED file, then
          │                       │  writes {DATA_ROOT}/allConcepts_manifest.csv (versioned)
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  QA consent counts    │  participants per consent group (DB, this run's loads) vs
          │                       │  dbGaP; a discrepancy makes the build UNSTABLE
          └───────────────────────┘

post { always }:  participant-db-backup  (pg_dump to S3, DB stays up; participant_db.tar overwritten only on SUCCESS/UNSTABLE,
                                          or when the run reached QA consent counts)
                  pipeline-report        (downstream-artifacts/pipeline-report/, archived and uploaded to S3)
```

Each stage runs only if the previous stage succeeded. Every stage sits inside one `ETL` parent
stage, which carries the run's [timeout](#timeouts). The participant database is long-lived: the
pipeline makes sure it is up before the first DB-backed stage and backs it up at the end; it is
never stopped. See [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) for its lifecycle, dump location,
and recovery.

The stage logic lives in [`etl-runners/common/orchestrator.groovy`](../etl-runners/common/orchestrator.groovy),
which `/Jenkinsfile` `load`s in its first stage; `/Jenkinsfile` itself keeps only the DAG and the
per-job parameters, well under the 64 KB "Method too large" limit Declarative compiles
`pipeline {}` into.

Per-study allConcepts runs after VCF rather than straight after the SSTR load: it needs only
the study's participants and consents, and placing it last means a generation failure stops
just the merge, not the global AllConcepts and VCF outputs.

---

## Trigger Modes

| Mode | `STUDY_ID` | Behavior |
|------|------------|----------|
| **Sweep** | blank | Loads every study marked "Data is ready to process" = Yes in managed inputs. Skips studies already marked "Data Processed". A study whose inputs are missing is [excluded](#missing-inputs-exclusion), and the build is UNSTABLE. |
| **Single study** | `phs######` | Loads exactly that study (reload or manual entry); the ready/processed flags do not apply. `INPUT` overrides the discovered SSTR URI. A missing input fails the build. |

`MANAGED_INPUTS` (an `s3://` URI) is **required in both modes**: the sweep's study list comes from
it, and every later stage (global AllConcepts, VCF indexes, the merge, the manifest) reads it on
its runner. A single study's SSTR is discovered from its study id under `DATA_ROOT`; its
abbreviation is never used to find it, and `INPUT` is never required.

---

## Pre-flight Only

`PREFLIGHT_ONLY=true` validates inputs without provisioning anything:

- `Package runner image` and `Start participant DB` are **skipped** (the database is neither
  started nor backed up);
- `Resolve studies` runs in full, S3 probes and [exclusions](#missing-inputs-exclusion) included;
- `sstr-populate-rds-participants`, `create-vcf-indexes`, and `all-concepts-data-generator`
  are triggered with `PREFLIGHT_ONLY=true`, running only their agent-side checks;
- `Generate global AllConcepts`, `Merge AllConcepts`, `AllConcepts manifest` and `QA consent
  counts` are **skipped**: they have no pre-flight mode, and the first and last need the database;
- the [end-of-run report](#end-of-run-report) is still written, marked pre-flight only.

---

## Stage 1: Build and Test

```
./mvnw clean package -DskipTests
./mvnw verify                       (or ./mvnw test if RUN_INTEGRATION_TESTS is off)
```

Builds the fat JAR and runs the test suites, plus `make -C etl-runners/participant-db test`,
`make -C etl-runners/pipeline-report test` (the report and the input probe, stubbed) and
`make -C etl-runners/qa-consent-counts test` (the QA's load-counts builder and validator). This is the
gate: nothing is provisioned or loaded until the suites pass. Downstream jobs skip tests
(`SKIP_TESTS=true`) to avoid re-running the same commit's suites per study.

---

## Stage 2: Resolve Studies

Reads the managed inputs CSV from S3 to build the list of studies to process, and validates the
run's parameters before anything is provisioned:

- `ALL_CONCEPTS_OUTPUT`, `VCF_INDEXES_OUTPUT`, `DATA_ROOT` and `MANAGED_INPUTS` must be `s3://`;
- `STUDY_CONCURRENCY` 1-50, `MERGE_ALLCONCEPTS_PARALLELISM` 1-16,
  `MERGE_ALLCONCEPTS_TIMEOUT_SECONDS` at most 28800 (inside the merge job's own 9 h limit, less
  1 h for provisioning and teardown; its one retry on exit 4 gets only what is left of the 9 h),
  the generator timeout parameters whole numbers, `MERGE_ALLCONCEPTS_INSTANCE_TYPE` set;
- the managed inputs must have the header columns `Study Abbreviated Name`, `Study Identifier`,
  `Data is ready to process`, `Data Processed` (exact names; the error lists what was found) and
  **no UTF-8 byte-order mark** (Excel's "CSV UTF-8" adds one, which renames the first column for a
  CSV reader); the ready/processed values must be Yes/No.

**In sweep mode:**
1. Selects studies where `Data is ready to process = Yes`. A row with a blank
   `Study Abbreviated Name` is ignored with a warning, as `ManagedInputsService` ignores it.
2. Splits into unprocessed (will run SSTR load, VCF indexes, and per-study allConcepts) and
   already-processed (skipped for all three, still covered by global AllConcepts, the merge and
   the manifest).
3. Validates all study IDs match `phs######` and checks for duplicates.
4. Probes each unprocessed study's inputs (below) and excludes any missing one.

**In single-study mode:**
1. Uses `STUDY_ID` directly; it need not be listed in managed inputs, and its flags do not apply.
2. `INPUT`, when set, is used as-is; otherwise the SSTR is discovered from the study id.
3. A missing input fails the build.

### Parallel Probing

All of the unprocessed studies' S3 inputs are probed in one shell call,
[`etl-runners/common/probe-study-inputs.sh`](../etl-runners/common/probe-study-inputs.sh), 16 studies at a
time (`xargs -P 16`): one `aws s3 ls` each of `rawData/`, the decoded data folder and the
mapping. It writes `downstream-artifacts/study-probe/probe.tsv` (SSTR candidates, decoded CSV count
and total bytes, mapping found), which the orchestrator parses. An S3 error (access denied,
throttling, a bad bucket) fails the stage outright: that is not a study's data problem, and
excluding studies over it would silently shrink the run. An empty prefix is simply "not found".

### SSTR Discovery

Staged SSTRs keep NHLBI's own file name, so the URI cannot be derived from the study id
alone. The pipeline lists `{DATA_ROOT}/{study_id}/rawData/` and picks the file by the same
rule `participants-migration` uses:

- a `.txt` whose name contains the study id and starts with `sstr_` or
  `bdc-ingestion-only__sstr_` (case-insensitive);
- the canonical `sstr_{phs}.{v}.txt` is preferred over folder-flattened copies
  (`SSTR__sstr_*`).

### Per-Study AllConcepts Inputs

Derived from the study's folder, like the SSTR, and checked to exist:

| Input | Location | Check |
|-------|----------|-------|
| decoded data CSVs | `{DATA_ROOT}/{study_id}/{DECODED_DATA_DIR}/` (default `decoded_data/`) | at least one `.csv` |
| concept mapping | `{DATA_ROOT}/{study_id}/{CONCEPT_MAPPING_FILE}` (default `mappings/mapping2.csv`) | the object exists |

The decoded CSVs' total size is recorded per study; Stage 8 uses it to order and time out the
generator builds.

### Missing-inputs Exclusion

In a **sweep**, an unprocessed study that lacks its SSTR or either allConcepts input is
**excluded** from the run instead of failing it:

- it is dropped from every per-study stage, and the build is marked **UNSTABLE** with every
  excluded study and why;
- it is listed in `downstream-artifacts/pipeline-studies.tsv` (`plan=excluded`, with the reason)
  and as `EXCLUDED` in the [end-of-run report](#end-of-run-report);
- every later stage (global AllConcepts, VCF indexes, the merge, the manifest) reads its ready list
  from managed inputs on its own, and would otherwise fail on the excluded study (the manifest
  finds no consent folders for it). So those stages get a copy of managed inputs with the excluded
  studies set to "Data is ready to process" = `No`, written once to
  `s3://<MANAGED_INPUTS bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/managed_inputs.csv`
  (and archived as `downstream-artifacts/managed_inputs.later-stages.csv`). Without exclusions they
  get `MANAGED_INPUTS` unchanged. The same approach as `Jenkinsfile.migration`.

It still **fails** — before the database is started or any runner is provisioned — in
**single-study** mode, and in a sweep where **every** study to load was excluded (that points at
`DATA_ROOT`, `DECODED_DATA_DIR` or `CONCEPT_MAPPING_FILE`, not at the data). To load an excluded
study, stage its files and re-run it with `STUDY_ID` (or mark it not ready).

---

## Stage 3: Package Runner Image

Skipped under `PREFLIGHT_ONLY`. The runner jobs (`sstr-populate-rds-participants`,
`generate-global-all-concepts`, `create-vcf-indexes`, `all-concepts-data-generator`,
`merge-allconcepts`, `generate-allconcepts-manifest` and `qa-consent-counts`) build nothing on the Jenkins host: this
stage builds the runner image once and passes it to every one of their builds as
`PREBUILT_IMAGE_TAR`, which makes them skip their own JAR build and image packaging. It runs
**before** `Start participant DB`, so a packaging failure leaves the database untouched. The
orchestrator deletes the image (S3 tarball, local tarball, and the host's podman image) in
`post { always }`. Run standalone, those jobs still build their own, and delete it when they finish.

---

## Stage 4: Start Participant DB

**Job:** `participant-db-start` ([`etl-runners/participant-db/Jenkinsfile.start`](../etl-runners/participant-db/Jenkinsfile.start))

Makes sure the participant database, a long-lived PostgreSQL 16 container on the Jenkins host, is
up, and publishes the app role's credentials (rotated on every start) as the SSM SecureString
`/hpds-etl/<ENV>/participant-db/app-credentials`, which each DB-backed runner reads at boot;
connections require TLS. It is never stopped between runs. A clean running database is kept;
`OVERWRITE_DB` replaces it from `DB_RESTORE_FROM` (blank = the latest clean backup), and after a
failed run `IF_LAST_RUN_FAILED` decides between restoring the clean backup (default) and
continuing on that run's data. A database that isn't up is created from the latest clean backup.
Skipped under `PREFLIGHT_ONLY`. Details: [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

---

## Stage 5: Load SSTR Participants

**Job:** `sstr-populate-rds-participants`
**Class:** [`SstrPopulateRdsParticipantsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/participants/SstrPopulateRdsParticipantsJob.java)
**Runs:** once per unprocessed study — **sequentially** by default; `PARALLEL_STUDY_LOADS=true`
loads them concurrently, at most `STUDY_CONCURRENCY` (default 5) at a time (correct, see
Concurrency in [`JENKINS.md`](JENKINS.md)). With `CONTINUE_ON_STUDY_FAILURE=false`, no new study
starts after the first failure (in parallel mode, builds already running finish). An abort or
the timeout stops every worker from starting another study. Each study's outcome, with the child
build's own reason for a failure, is printed in the stage summary and recorded in
`downstream-artifacts/study-outcomes/sstr.tsv` for the [report](#end-of-run-report).

### Input

A dbGaP SSTR subject/sample mapping file (tab-delimited) with columns:
- `dbgap_subject_id`
- `dbgap_sample_id`
- `CONSENT` (consent group code)
- `consent_abbreviation`

### Flow

```
Read SSTR file (from S3 or local)
        │
        ▼
Purge existing consents for this study_id
        │
        ▼
Resolve or create participants           one per distinct dbgap_subject_id
  (ON CONFLICT DO NOTHING, re-read)      source = "DBGap"; integer hpds_id from hpds_id_seq
        │
        ▼
Upsert consents                          one per participant
  (ON CONFLICT DO UPDATE)                keyed by consent group from the file
        │
        ▼
Upsert samples                           one per non-blank dbgap_sample_id
  (ON CONFLICT DO NOTHING)
        │
        ▼
All within one transaction per study
```

### Output

Populated participant database tables: `participants`, `consents`, `samples`. A JSON report
with row/insert counts: `rowsRead`, `distinctParticipants`, `participantsInserted`,
`consentsWritten`, `samplesSeen` (input rows with a non-blank `dbgap_sample_id`),
`samplesInserted` (fewer than `samplesSeen` on a reload: samples are inserted with
`ON CONFLICT DO NOTHING`), `submittedSamplesInserted`, `countsByConsentGroup`.

---

## Stage 6: Generate Global AllConcepts

**Job:** `generate-global-all-concepts`
**Class:** [`GenerateGlobalAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateGlobalAllConceptsJob.java)
**Runs:** once, covering all ready studies (skipped under `PREFLIGHT_ONLY`)

### Input

- Managed inputs CSV (`MANAGED_INPUTS`, to discover all ready studies; with [exclusions](#missing-inputs-exclusion), the copy that marks them not ready)
- Participant database tables: `participants`, `consents`, `samples` (populated by Stage 5)

### Flow

```
Read managed inputs ─▶ filter to ready studies
        │
        ▼
For each ready study:
  ├─ Query consents        ─▶ build _consents concept rows
  │                           (consent group membership per patient)
  │
  ├─ Query participants    ─▶ build _source_subject_id concept rows
  │                           (participant identifier per patient)
  │
  ├─ Query samples         ─▶ build _source_sample_id concept rows
  │                           (sample identifier per patient)
  │
  └─ Combine study + consent info  ─▶ build _studies_consents concept rows
                                      (study-level and individual consent paths)
        │
        ▼
Aggregate all studies into one CSV
        │
        ▼
Write global_AllConcepts.csv to ALL_CONCEPTS_OUTPUT (s3://)
```

### Output

A single `global_AllConcepts.csv` containing concept rows for consent, subject, sample, and
study metadata across all ready studies.

---

## Stage 7: Create VCF Indexes

**Job:** `create-vcf-indexes`
**Class:** [`CreateVCFIndexesJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/genomic/CreateVCFIndexesJob.java)
**Runs:** once, only if there are unprocessed studies. Receives `MANAGED_INPUTS` (the
exclusion copy when studies were excluded) and `PREFLIGHT_ONLY`. An UNSTABLE result marks the
build UNSTABLE and continues; anything worse
fails it.

### Input

- Managed inputs CSV (to find studies with "G" in their data type)
- Participant database tables: `consents`, `samples` (for genomic studies)

### Flow

```
Read managed inputs ─▶ filter to genomic studies (data type contains "G")
        │
        ▼
For each genomic study:
  Query consents + samples
        │
        ▼
  For each consent group with NWD-prefixed samples:
    ├─ Generate vcfIndex.tsv        one row per chromosome (1-22, X)
    │                               with sample/patient ID lists
    │
    └─ Generate SampleIds.csv       list of sample IDs in this consent group
        │
        ▼
Write per-consent files to VCF_INDEXES_OUTPUT:
  {studyId}.c{code}_vcfIndex.tsv
  {studyId}.c{code}_SampleIds.csv
```

### Output

Per-consent-group VCF index and sample ID files for genomic studies.

**Held-back consent groups.** Only a consent group whose data would produce unparseable files is
held back; every good group of the run is still written:

- `ID_COUNT_MISMATCH`: one of the group's NWD sample ids contains a comma, tab or line break, so
  `sample_ids` and `patient_ids` would not line up (only that group is held back);
- `BAD_STUDY_ABV`: the study abbreviation contains a comma or line break, which would shift or
  split every `_SampleIds.csv` line, so every group of that study with NWD samples is held back (a
  study with nothing to index is not).

A held-back group is not written, and its **previously published** `_vcfIndex.tsv` /
`_SampleIds.csv`, where they exist, are **deleted**, so stale data cannot be read beside this
run's groups (the bucket is versioned, so the old versions stay recoverable: with a group to hold
back, the job refuses an `s3://` output whose bucket does not have versioning Enabled, exit 5,
before anything is written or deleted). Each held-back group
is an output-validation ERROR (exit 2, so the build fails) whose message says which files were
deleted. The report's `metrics.heldBackGroups` lists each one with `consentGroup`, `studyId`,
`consent`, `studyAbv`, `reasons`, `badSampleIdCount`, up to 10 `badSampleIds` and
`deletedFiles`; `metrics.heldBackFilesDeleted` counts the deletions. The runner's `validate.sh`
prints them, and the [end-of-run report](#end-of-run-report) lists them with their deleted files.
`NO_FILES_WRITTEN` is an error only when nothing at all was written.

---

## Stage 8: Generate Per-Study AllConcepts

**Job:** `all-concepts-data-generator`
**Class:** [`AllConceptsDataGeneratorJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/AllConceptsDataGeneratorJob.java)
**Runs:** once per unprocessed study, **always concurrently**: at most `STUDY_CONCURRENCY`
(default 5) at a time, whatever `PARALLEL_STUDY_LOADS` says — the generator only reads the
participant DB, so the write-contention reasons for loading SSTRs one at a time do not apply.
Receives `PREFLIGHT_ONLY`, `SKIP_ANALYSIS` and a per-study `JOB_TIMEOUT_SECONDS`.

- **Largest first.** Studies start in decreasing order of decoded data size (summed from the
  Resolve-studies listing), so the longest builds do not trail the stage alone.
- **Size-scaled timeout.** Each study's `JOB_TIMEOUT_SECONDS` is
  `GENERATOR_TIMEOUT_BASE_SECONDS` (default 1800) plus `GENERATOR_TIMEOUT_SECONDS_PER_GB`
  (default 600) per GiB of decoded data (rounded up), clamped to **7200-12600 s**: never less than
  the generator job's own default (2 h), never more than 3.5 h, which leaves provisioning time
  inside that job's 4 h Jenkins timeout (beyond it, the job would be killed before its runner's
  monitor could report the timeout).
- Outcomes go to `downstream-artifacts/study-outcomes/per-study-all-concepts.tsv`.

### Input

- the study's decoded data CSVs and concept mapping ([derived in Resolve studies](#per-study-allconcepts-inputs))
- participant database tables `participants` and `consents`, populated for the study by Stage 5

### Flow

```
Read the concept mapping ─▶ (unless SKIP_ANALYSIS) re-analyse each column's data type
        │                    against the decoded data, dropping empty columns
        ▼
Stream each decoded data CSV ─▶ resolve each patient to its hpds_id ─▶ route rows to the
                                 patient's consent group
        ▼
Overwrite {DATA_ROOT}/{study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv
for every consent group with rows
        ▼
Delete the study's per-study file in any other allConcepts/c{code}/ folder of the study (a group that
produced no rows this run, or that a reload removed), whichever job wrote it -- skipped for the
whole study when any mapped data file is missing (warning)
```

### Output

Per-consent allConcepts files under `{DATA_ROOT}/{study_id}/allConcepts/` — the same folders
the migration's `split-allconcepts` writes to, and which other sources will write into too.

- **Overwrite, not append.** Each run replaces the study's files in place; S3 versioning keeps
  every earlier version (including a migrated split file it replaces). The runner's pre-flight
  **refuses an output bucket without versioning Enabled**.
- **The run fully replaces the study's prior per-study output.** After a run,
  `{study_id}/allConcepts/c{code}/` holds exactly this run's `{study_id}_allConcepts_c{code}.csv`
  files: any prior per-study allConcepts output for the study in those folders is overwritten or,
  for a group with no rows this run, deleted — whichever job wrote it, including the migration's
  `split-allconcepts`. A delete on a versioned bucket is a recoverable marker, and it changes the
  folder's source fingerprint, so the merge rebuilds it (`SOURCES_CHANGED`). Each removal is a
  `STALE_OUTPUT_REMOVED` warning (build UNSTABLE).
- **A missing mapped data file skips stale cleanup for the whole study.** If a data file the
  mapping names is missing (`MISSING_DATA_FILE` warning), the generator deletes no stale file of
  the study: decoded data files are not split by consent (each participant's consent comes from
  the SSTR), so a missing file could have fed any consent group, and a group with no rows may then
  only mean its data was not read. The files it did write still replace the earlier ones. The
  report records it as a `STALE_CLEANUP_SKIPPED` warning and in its metrics: `staleCleanupSkipped`
  (`true`) and `staleFilesKept` (the stale files left in place). `staleFilesRemoved` lists what
  was deleted when cleanup ran.
- An invalid `STUDY_ID` (not `phs` + 6 digits) is rejected before anything runs (`BAD_STUDY_ID`).
- The runner's `validate.sh` checks the report, that there is one file per consent group with
  rows, and that every listed file is really in S3. Its warnings (`validate-report.sh` exit 10)
  make the child build, and so this one, UNSTABLE.

### Failure

The stage runs every study (unless `CONTINUE_ON_STUDY_FAILURE` is off), prints a per-study
summary, and **fails the build before Merge AllConcepts** if any study failed. A merged file
built while a study is missing or half-written would silently lack its rows. Fix the study
and re-run it with `STUDY_ID`; its files are simply overwritten.

---

## Stage 9: Merge AllConcepts

**Job:** `merge-allconcepts`
**Class:** [`MergeAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/MergeAllConceptsJob.java)
**Runs:** once (skipped under `PREFLIGHT_ONLY`). Touches no database.

### Input

- `DATA_ROOT`: the `s3://` root of the per-study folders. The bucket **must have versioning
  Enabled**; the job (and its pre-flight) refuses otherwise.
- `MANAGED_INPUTS`: every study marked ready is checked (the exclusion copy when studies were
  excluded). `STUDY_IDS` checks just those studies instead: the permanent orchestrator passes it
  in single-study mode (`STUDY_IDS=<STUDY_ID>`, so a reload merges exactly that study) and leaves it
  blank in a sweep; it is also there for manual runs.
- `INSTANCE_TYPE` (`MERGE_ALLCONCEPTS_INSTANCE_TYPE`, default `c5.xlarge`),
  `PARALLELISM` and `JOB_TIMEOUT_SECONDS` are passed explicitly: a parameter left out of a build
  call gets the default the child job registered on its *previous* build, so the first run after a
  deploy would otherwise use a stale one.

Only `{study_id}/allConcepts/c{code}/` folders are scanned, never `{study_id}/legacy/allConcepts/`
or the rest of the study folder. A folder's **sources** are the files directly inside it whose
name contains `_allConcepts_`, from every producer: Stage 8's generator, the migration's
`split-allconcepts`, and further sources to come. Anything else (other names, nested folders, a
MERGED file named for another folder) is ignored and listed in the report as `IGNORED_FILES`.

### Flow

```
Require versioning on the bucket; read the ready studies from managed inputs
        │
        ▼
For each ready study: list the current version of every object under {study}/allConcepts/
        │
        ▼
For each consent folder, compare the version-id fingerprint of its current sources with the one
recorded on {study}_c{code}_allConcepts_MERGED.csv:
  MISSING              no MERGED file                              ─▶ merge
  SOURCES_CHANGED      a source was added, overwritten or deleted  ─▶ re-merge (overwrite)
  NO_FINGERPRINT       MERGED file predates fingerprints            ─▶ re-merge (once)
  REMOVED_NO_SOURCES   MERGED file but no sources left              ─▶ delete it (warning)
  CHANGED_DURING_RUN   no sources, but the MERGED file was rewritten ─▶ leave it (warning)
                       or deleted since the scan
  ONLY_MERGED_COPIES   no sources, only merged copies (a legacy      ─▶ leave it (warning)
                       *_merged.csv, another folder's MERGED file)
  NO_SOURCES           no allConcepts files at all                  ─▶ nothing (warning)
  UP_TO_DATE           fingerprints match                           ─▶ skip
```

**How "changed since the merge" is decided.** On a versioned bucket every write gives a key a new
version id. A MERGED file records, in its own S3 metadata, `source-fingerprint` (SHA-256 of its
sources' sorted `name<TAB>versionId` lines), `source-count`, and `content-md5` (MD5 of its
content). An added file, an overwrite (even with identical bytes) or a deletion changes the
current sources' fingerprint, so the folder is re-merged. No timestamps or delete markers are
involved, so a file written while a merge runs can't be missed.

**Merging.** Sources are read at exactly the versions fingerprinted and concatenated in name
order. Each source is ended with a newline, so one missing its final newline can't run its
last row into the next file's first row. Nothing is written to local disk, since a merged
consent file can be larger than the runner's root volume. S3 only takes metadata when an object
is created, so the sources are read twice. The first read computes the MD5 and length. The
second streams them to the MERGED key (multipart above 4 GiB) with that metadata, overwriting
the old object; S3 keeps the previous version. If the second read hashes differently, the
upload is aborted and no new version is written.

**Parallelism.** Folders are checked and merged `--parallelism` at a time (Jenkins
`PARALLELISM`, orchestrator `MERGE_ALLCONCEPTS_PARALLELISM`; default 8, at most 16), largest
total source size first so the biggest aren't left running alone at the end. Each merge in
flight holds up to two S3 connections and, above the multipart threshold, one 64 MiB upload part
in memory. Folders are independent, so a failing folder doesn't stop the others. Every folder is
attempted, the ones that merged stay merged (and show up to date on a retry), and the run then
fails with the first failure in folder order. Because a failed run's report carries no
metrics, it also writes every folder's outcome (the failed ones as `FAILED`, with their error) to
`merge-allconcepts-<runId>-folders.json` in its reports, archived on the build; the
[end-of-run report](#end-of-run-report) turns each failed folder into a review item for its study.

### Output

An up-to-date `{study_id}_c{code}_allConcepts_MERGED.csv` in each consent folder of every ready
study, and a report with `studiesChecked`, `consentFoldersDiscovered`, `foldersMerged`,
`foldersSkipped`, `foldersRemoved`, `studiesWithoutFolders`, and the action per folder.
Warnings (build UNSTABLE): a ready study with no consent folders (`STUDY_HAS_NO_CONSENT_FOLDERS`),
a folder with no source files (`CONSENT_FOLDER_WITHOUT_SOURCES`), a MERGED file deleted because its
sources are gone (`MERGED_WITHOUT_SOURCES_REMOVED`), a folder holding only merged copies
(`ONLY_MERGED_COPIES`, left unchanged), and a MERGED file that changed while the run checked it
(`MERGED_CHANGED_DURING_RUN`, left for the next run). A malformed `STUDY_IDS` entry (blank, or not
`phs` + 6 digits) is rejected before anything runs (`BAD_STUDY_ID`).

Runs only if Stage 8 succeeded (or was skipped because no study is unprocessed). Each
re-merge leaves the previous MERGED version behind as a noncurrent S3 version; see
[Merged-file Lifecycle](#merged-file-lifecycle-s3-versions).

---

## Stage 10: AllConcepts Manifest

**Job:** `generate-allconcepts-manifest`
**Class:** [`GenerateAllConceptsManifestJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateAllConceptsManifestJob.java)
**Runs:** once, after the merge (skipped under `PREFLIGHT_ONLY`). Touches no database.

Writes **`{DATA_ROOT}/allConcepts_manifest.csv`**, the file handed to production ingestion:

1. Always first, a row with partition `global` for the current version of
   `global_AllConcepts.csv` from Stage 6 (`ALL_CONCEPTS_OUTPUT`).
2. Then one row per consent group of every study marked ready in `MANAGED_INPUTS`, sorted by
   study, then consent in code order (`c2` before `c10`). Managed inputs name only studies; a
   study's consent groups are the `c{code}/` folders it actually has.

| Column | Value |
|--------|-------|
| `partition` | `{study_id}.{consent}` from the study and its consent folder (`phs000001` / `c1` → `phs000001.c1`), or `global` |
| `s3_uri` | the MERGED file, or `global_AllConcepts.csv` |
| `version_id` | the file's S3 version id; later runs overwrite the key, so this pins the exact data described |
| `last_updated` | that version's S3 `LastModified` (ISO-8601 UTC) |
| `md5` | MD5 of that version's content: recorded by the merge for a MERGED file; computed by reading that exact version for the global file |

**Checks before writing.** Every ready consent group must have a MERGED file whose recorded
fingerprint equals that of the folder's current sources (it is the merge of exactly the ready
files) and which carries its md5. A ready study must have at least one consent folder. Any
failure (`MERGED_MISSING`, `MERGED_STALE`, `MERGED_NOT_FINGERPRINTED`, `MD5_MISSING`,
`NO_CONSENT_FOLDERS`, `NO_READY_STUDIES`), a global file that is missing, empty or unversioned
(`GLOBAL_MISSING`, `GLOBAL_EMPTY`, `GLOBAL_NOT_VERSIONED`), or a run in which every consent folder
of every ready study was left out with a warning (`NO_CONSENT_GROUPS`: a manifest of the global row
alone would drop every study from ingestion) means **no manifest is written**: the previous
version stays current, and the job exits `2` listing every problem at once.

Left out of the manifest **with a warning** (build UNSTABLE) instead:

- a consent folder holding no allConcepts files at all (`NO_SOURCES`);
- a consent folder holding **only merged copies**: no per-study source, so the merged file there
  cannot be verified against anything. `MERGED_WITHOUT_SOURCES` when only its own MERGED file is
  left, `ONLY_MERGED_COPIES` when it holds other merged copies (a legacy `*_merged.csv`, another
  folder's MERGED file). The warning points at that study's allConcepts generation (re-run it with
  `STUDY_ID`), which writes the sources the merge and the manifest need;
- a MERGED file that is empty (`MERGED_EMPTY`: 0 bytes, because its sources are empty);
- and, on top of the folder warnings, a ready study none of whose consent folders made it into the
  manifest (`STUDY_NOT_IN_MANIFEST`).

**Versioning.** The manifest is overwritten in place, on a bucket that must have versioning
Enabled, as must the global file's bucket (both checked by the job and its pre-flight); every earlier manifest stays retrievable as an
older version. The job fails if S3 returns no version id for the write. Metadata `row-count` and
`run-id` are set on each version, and a copy is archived with the build's reports. Each row pins
a `version_id`; see [Merged-file Lifecycle](#merged-file-lifecycle-s3-versions) for how long those
versions stay resolvable.

## Stage 11: QA Consent Counts

**Job:** `qa-consent-counts` ([`etl-runners/qa-consent-counts/`](../etl-runners/qa-consent-counts/))
**Class:** [`QaConsentCountsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/qa/QaConsentCountsJob.java)
**Runs:** once, last (skipped under `PREFLIGHT_ONLY`). Reads the participant DB; writes nothing to it.

For every study marked ready in managed inputs, counts the distinct participants per consent
group in the participant DB's `consents` table and compares them with dbGaP's `subject_count` for
that consent group of the study **version** in managed inputs (`Version`, e.g. `v4`), read from
the dbGaP SSTR API: `GET https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study/{phs}.{v}/summary`
(`study.consent_groups[]`). It also checks the DB against what this run's SSTR loads reported
(`metrics.countsByConsentGroup` of each `sstr-populate-rds-participants` report): before
triggering the job, the stage turns those reports into a load-counts CSV
([`build-load-counts.py`](../etl-runners/qa-consent-counts/build-load-counts.py), successful loads
only) and uploads it to
`s3://<DATA_ROOT bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/qa_load_counts.csv`
(`--load-counts`). A study this run did not load is compared DB vs dbGaP only. **The DB is
authoritative**: a load report that disagrees with it is a discrepancy too.

Consent codes are matched after normalizing (`c1`, `01` and `1` are one group). A group on one
side only counts as 0 on the other. Consent 0 is compared like any other code.

| Status | Meaning |
|--------|---------|
| `MATCH` | dbGaP, the DB (and the load report, when it covers the study) agree |
| `COUNT_MISMATCH` | dbGaP and the DB both have the group, with different counts |
| `MISSING_IN_DB` | dbGaP lists subjects in a group the DB has none in (DB 0) |
| `MISSING_IN_DBGAP` | the DB has participants in a group dbGaP does not list (dbGaP 0) |
| `DBGAP_COUNT_MISSING` | dbGaP lists the group without a `subject_count` |
| `REPORT_DB_MISMATCH` | this run's load report disagrees with the DB |
| `NO_VERSION` | the study has no usable `Version` in managed inputs; not looked up |
| `VERSION_NOT_FOUND` | dbGaP has no such study version (HTTP 400, `error_code` 2); never retried |
| `VERSION_MISMATCH` | dbGaP answered for a different accession or version than requested; its counts are not compared |
| `UNVERIFIED` | dbGaP gave no usable answer after every retry (see below) |

A row can carry two findings (`COUNT_MISMATCH;REPORT_DB_MISMATCH`). `study_status` is not checked.

**Outcome.** Every discrepancy is a WARNING issue, so the job succeeds with warnings, its
`validate.sh` exits 10, and the stage and build are **UNSTABLE**; the pipeline still finishes.
dbGaP is retried per study — up to 5 attempts, waiting 2 s, 4 s, 8 s, 16 s (doubling, capped at
60 s; a `Retry-After` header, seconds or HTTP-date, is honoured instead, up to 5 minutes), 60 s per
request — on 429, 408, 5xx, timeouts, connection errors and a 200 whose body is not the expected
JSON. Any other 4xx is not retried. So that an outage ends quickly, with its report, rather than
in the runner's monitor timeout (exit 124, nothing written), no lookup starts once 3 studies in a
row got no usable answer, or 90 minutes into the lookups; those studies are `UNVERIFIED` too. A
study still unanswered **fails the job with exit 4**
(retryable; the runner retries once with `RETRY_ON_INFRA`), and the build: every other study is
still compared, and both CSVs and the JSON report (metrics and issues included) are still written,
with that study's rows `UNVERIFIED`. Because this stage only reads the database, reaching it means
every stage that writes it succeeded, so a QA failure does **not** stop the
[backup](#post-back-up-participant-db) from being promoted. Four studies are looked up at a time
(each call takes ~10 s).

**Outputs** (in the build's archived reports, and copied into the
[end-of-run report](#end-of-run-report)): `qa_consent_counts.csv` (one row per study and consent
code) and `qa_consent_count_discrepancies.csv` (its non-`MATCH` rows), columns `study_id, version,
consent_code, consent_short_name, dbgap_subjects, db_participants, load_participants, load_source,
status, note`; and the JSON report's metrics `studiesChecked`, `studiesVerified`,
`consentsCompared`, `consentsMatching`, `discrepancies`, `discrepanciesByType`,
`studiesWithDiscrepancies`, `unverifiedStudies`, `apiAttempts`, `apiRetries`.

**Network.** The runner's security group must allow HTTPS (443) egress to
`www.ncbi.nlm.nih.gov`; see [`JENKINS.md`](JENKINS.md#qa-consent-counts-network-egress).

---

## Post: Back Up Participant DB

**Job:** `participant-db-backup` ([`etl-runners/participant-db/Jenkinsfile.backup`](../etl-runners/participant-db/Jenkinsfile.backup))

Runs in `post { always }` whenever this build's start succeeded. It `pg_dump`s the database to
S3 and verifies the upload; the database stays up. The dump becomes the latest clean backup
(`participant_db.tar`) only if the build result so far is SUCCESS or UNSTABLE, or the run reached
[QA consent counts](#stage-11-qa-consent-counts) (which only reads the database, so every stage
that writes it had succeeded); any other failed or aborted run is dumped to `participant_db_not_promoted.tar` instead and the live database is
marked as holding a failed run, which the next start handles per `IF_LAST_RUN_FAILED`. If the
backup fails, the build fails: re-run `participant-db-backup`. See
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

The backup is bounded by its own 4 h `timeout` inside `post` (the backup job itself times out at
3 h); a backup that does not finish fails the build with the same instruction. It runs outside the
[run's timeout](#timeouts), so a long run never squeezes it.

---

## End-of-run Report

Written in `post { always }` after the backup — whatever stage the build stopped at, pre-flight
runs included — by [`etl-runners/pipeline-report/pipeline-report.py`](../etl-runners/pipeline-report/pipeline-report.py)
(python3 stdlib). It reads only what the run collected under `downstream-artifacts/`:
`pipeline-studies.tsv` (every study and its plan, from Resolve studies), `study-outcomes/*.tsv`
(each per-study build's result and the child's own failure reason), and the copied JSON reports
of every child (`sstr/<study>/`, `per-study-all-concepts/<study>/`, `all-concepts/`,
`vcf-indexes/`, `merge-allconcepts/`, `allconcepts-manifest/`, `qa-consent-counts/`).

It writes `downstream-artifacts/pipeline-report/`, archived on the build and uploaded to
`s3://<DATA_ROOT bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/pipeline-report/`:

| File | Content |
|------|---------|
| `pipeline-report.md` | for people: first the **Consent count QA (dbGaP)** section, then study statuses, each stage's outcome, studies needing follow-up (with why and what to do), all studies |
| `pipeline_study_outcomes.csv` | one row per study: `study_id, abv, status, plan, sstr_result, sstr_build_url, participants, participants_inserted, allconcepts_result, allconcepts_build_url, consent_groups, rows_processed, decoded_bytes, review_items, reasons` |
| `pipeline-report.json` | all of the above plus every review item, `heldBackVcfGroups` and `consentCountQa` (`reportVersion` 1) |
| `qa_consent_counts.csv`, `qa_consent_count_discrepancies.csv` | copies of the [QA's](#stage-11-qa-consent-counts) CSVs, when it ran |

The **Consent count QA (dbGaP)** section comes first: a summary line and a table of the
discrepancies only (at most 200; the CSV has every one), or "All N consent group(s) match dbGaP";
"QA did not run" when the pipeline stopped before it; "QA failed: <exit>: <reason>" (with the
unverified studies, and the discrepancies among the studies it did check) when dbGaP could not
answer. QA findings are not turned into study review items: the section stands on its own.

Study status, most severe first: `FAILED` (a stage failed for it), `EXCLUDED` (missing inputs),
`NOT_REACHED` (the pipeline stopped first), `COMPLETE_WITH_WARNINGS`, `COMPLETE`,
`ALREADY_PROCESSED` (not reloaded). Run-wide stages' ERROR/WARNING issues that name a study are
attributed to it. A study is `COMPLETE` only once global AllConcepts, the merge and the manifest
have run: if one of them did not run it is `NOT_REACHED`, and if one failed without naming a
study, every study it covered is `FAILED` (`STAGE_FAILED`) — its outputs were not published.
(Pre-flight runs skip that check.) VCF consent groups [held back](#stage-7-create-vcf-indexes) for
bad data fail their study (their ERROR issues name it) and are also listed in a "Held-back VCF
consent groups" section (and `heldBackVcfGroups`) with the previously published files deleted for
each. When the merge failed, its `merge-allconcepts-<runId>-folders.json` (not mistaken for its
report) turns every failed folder into a `MERGE_FOLDER_FAILED` item for its study. The report never changes the build's result; a failure to write or upload it
is a warning. Test: `make -C etl-runners/pipeline-report test` (a fixture `downstream-artifacts/`
against expected outputs; `UPDATE=1` regenerates them after an intended change).

---

## Timeouts

| Scope | Limit | Why |
|-------|-------|-----|
| Every stage (the `ETL` parent stage) | 72 h | A backstop only: stuck children are caught by their own timeouts. A sweep of ~30 new studies boots two runners per study (SSTR load one at a time by default, then allConcepts), and the merge alone may take 4 h — 12 h did not fit. |
| `post { always }` DB backup | 4 h | The backup job's own limit is 3 h. |
| `post { always }` report | 15 min | |
| Each child job | its own `options { timeout }` and `JOB_TIMEOUT_SECONDS` | e.g. generator 4 h / size-scaled, merge 9 h (two 4 h attempts by default; a retry is capped to what is left) / `MERGE_ALLCONCEPTS_TIMEOUT_SECONDS` |

The run's timeout is on a parent stage rather than in the pipeline's `options {}` on purpose: a
pipeline-level `options { timeout }` also wraps `post {}` (Declarative runs post inside the
pipeline's option wrappers), so when it fired it would cut short the DB backup that must follow.

---

## Merged-file Lifecycle (S3 Versions)

The merge overwrites each `{study_id}_c{code}_allConcepts_MERGED.csv` in place, so **every
re-merge leaves a full noncurrent version** of the previous MERGED file in the versioned bucket
(as do the generator's overwrites of per-study files). Over many runs this grows without bound.

Recommended: an S3 lifecycle rule on the `DATA_ROOT` bucket (prefix-filtered to it) with
`NoncurrentVersionExpiration` — keep the N newest noncurrent versions (`NewerNoncurrentVersions`)
and/or expire them N days after they become noncurrent (`NoncurrentDays`) — plus expiry of
expired-object delete markers.

Trade-off: `allConcepts_manifest.csv` pins each row to a `version_id`. Once a version an
**older** manifest pins is expired, that older manifest no longer resolves; the current manifest
is unaffected (it pins current versions). Choose N to cover how far back you may need to re-ingest
(e.g. keep at least as many noncurrent versions as manifests you would ever roll back to).

---

## Data Flow Summary

```
                    SSTR file / Subject CSV
                           │
                           ▼
                   ┌────────────────┐        restored from / dumped to
                   │ Participant DB │ ◀────▶ s3://…/participant-db/<env>/backups/
                   │  participants  │
                   │  consents      │
                   │  samples       │
                   └───────┬────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
     global_AllConcepts  per-study    VCF indexes
         .csv          allConcepts   + SampleIds
                         .csv          .csv/.tsv
              │            │            │
              │            ▼            │
              │     merge-allconcepts   │
              │     *_MERGED.csv        │
              │            │            │
              │            ▼            │
              │  generate-allconcepts-  │
              │  manifest               │
              │  allConcepts_manifest   │
              │  .csv                   │
              └────────────┼────────────┘
                           ▼
                     HPDS ingestion
```

---

## Parameters

Data paths below are under `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/`
(abbreviated `…/`).

| Parameter | Default | Description |
|-----------|---------|-------------|
| `STUDY_ID` | (blank) | Blank for sweep mode; `phs######` for single study |
| `INPUT` | (blank) | With `STUDY_ID`: the SSTR URI, overriding discovery |
| `MANAGED_INPUTS` | (blank) | **Required.** Managed inputs CSV, `s3://` (UTF-8 without a BOM) |
| `DATA_ROOT` | `…/BAM_testing` | Root of the per-study folders, one per study id (must be `s3://`, versioned bucket). SSTRs are discovered in `{DATA_ROOT}/{study_id}/rawData/`; the generator writes `{study_id}/allConcepts/c{code}/` files there and the merge reads them; shared with the migration's `DATA_ROOT` |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `SSTR_JOB` | `sstr-populate-rds-participants` | SSTR runner job |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep loading remaining studies when one fails |
| `PARALLEL_STUDY_LOADS` | `false` | Run the SSTR loads concurrently instead of one study at a time (per-study allConcepts is always concurrent) |
| `STUDY_CONCURRENCY` | `5` | Most per-study builds (and EC2 runners) at once, 1-50: per-study allConcepts always, the SSTR load with `PARALLEL_STUDY_LOADS`; the rest wait for a free slot |
| `PREFLIGHT_ONLY` | `false` | Validate inputs and stop without provisioning (see [Pre-flight Only](#pre-flight-only)) |
| `ALL_CONCEPTS_JOB` | `generate-global-all-concepts` | Global AllConcepts runner job |
| `ALL_CONCEPTS_OUTPUT` | `…/global_allconcepts/` | Output location for global AllConcepts (must be `s3://`) |
| `VCF_INDEXES_JOB` | `create-vcf-indexes` | VCF index runner job |
| `VCF_INDEXES_OUTPUT` | `…/vcf_indexes/` | Output location for VCF indexes (must be `s3://`) |
| `ALL_CONCEPTS_GENERATOR_JOB` | `all-concepts-data-generator` | Per-study allConcepts runner job |
| `DECODED_DATA_DIR` | `decoded_data` | Decoded data folder, relative to `{DATA_ROOT}/{study_id}/` |
| `CONCEPT_MAPPING_FILE` | `mappings/mapping2.csv` | Concept mapping file, relative to `{DATA_ROOT}/{study_id}/` |
| `SKIP_ANALYSIS` | `false` | Use the mapping's data types as-is instead of re-analysing |
| `GENERATOR_TIMEOUT_BASE_SECONDS` | `1800` | Per-study allConcepts timeout: this plus the per-GiB allowance, clamped to 7200-12600 s |
| `GENERATOR_TIMEOUT_SECONDS_PER_GB` | `600` | Seconds per GiB of the study's decoded data |
| `MERGE_ALLCONCEPTS_JOB` | `merge-allconcepts` | Merge runner job |
| `MERGE_ALLCONCEPTS_PARALLELISM` | `8` | Consent folders merged at once, 1-16 |
| `MERGE_ALLCONCEPTS_TIMEOUT_SECONDS` | `14400` | How long the merge may run before it is failed as stuck (exit 124), at most 28800 |
| `MERGE_ALLCONCEPTS_INSTANCE_TYPE` | `c5.xlarge` | Merge runner instance type, passed explicitly so a stale registered default is never used |
| `ALLCONCEPTS_MANIFEST_JOB` | `generate-allconcepts-manifest` | Manifest runner job |
| `QA_CONSENT_COUNTS_JOB` | `qa-consent-counts` | Consent count QA runner job (last stage); its dbGaP settings are passed explicitly (see [Stage 11](#stage-11-qa-consent-counts)) |
| `DB_START_JOB` | `participant-db-start` | Job that starts the participant database |
| `DB_BACKUP_JOB` | `participant-db-backup` | Job that dumps it at the end of the run (it stays up) |
| `OVERWRITE_DB` | `false` | Replace the database before this run from `DB_RESTORE_FROM` (blank = the latest clean backup), saving a running one first (see [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md#start-rules)) |
| `DB_RESTORE_FROM` | (blank) | What a new or replaced database is loaded from: blank = latest clean backup, `none`, a file under `backups/`, or an `s3://` URI |
| `IF_LAST_RUN_FAILED` | `restore-clean` | If the previous run failed: `restore-clean` replaces its data from the latest clean backup; `continue` builds on it |
| `ENV` | `development` | Selects `etl-runners/environments/<ENV>.tfvars` |

Job-name parameters resolve relative to the `hpds-etl` Jenkins folder.

---

## Exit Codes

| Code | Name | Meaning |
|-----:|------|---------|
| 0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | Study loaded; warnings mark the build UNSTABLE |
| 1 | `UNKNOWN` | Unhandled failure |
| 2 | `VALIDATION_FAILED` | Input or output validation failed |
| 3 | `DATA_ERROR` | Data-level failure; study transaction rolled back |
| 4 | `INFRASTRUCTURE_ERROR` | Retryable infrastructure issue |
| 5 | `CONFIG_ERROR` | Missing parameter, disabled job, or participant database not running |

---

## Future Improvements

- **Per-study stages on pooled runners.** Every per-study build (SSTR load, per-study
  allConcepts) boots its own EC2 runner, so a sweep spends two boots per study. The generalized
  runner pool — worker mode in `terraform-modules/etl-runner`, `etl-runners/common/pool-dispatch.sh`,
  `etl-runners/common/pool-retire-worker.sh`, `etl-runners/runner-pool` — lets a fixed set of
  workers boot once and take study after study (the migration's split stage uses it). It is
  available on the permanent branches but **not yet wired** into this pipeline.

---

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) -- participant database lifecycle and dumps
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) -- adding a new job to the framework
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags
