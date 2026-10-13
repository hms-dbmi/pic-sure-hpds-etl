# Migration Pipeline (Temporary)

A one-off pipeline that migrates legacy HPDS data into the new participant database. It
populates the `participants`/`consents`/`samples` tables from legacy mapping files, splits
existing allConcepts files by consent group (replacing old HPDS ids with the new integer
HPDS ids), then generates the global AllConcepts, VCF indexes, and merged allConcepts from the
migrated data.

Orchestrated by [`/Jenkinsfile.migration`](../Jenkinsfile.migration) (Jenkins job
`new-hpds-etl-participant-migration-pipeline` in the `hpds-etl` folder). The first two jobs
have `JobType.MIGRATION`; the rest are `JobType.PERMANENT` jobs reused here to build the
derived artifacts from newly migrated data.

**This pipeline is temporary.** Once the migration has run in every environment, delete:
- `Jenkinsfile.migration`
- `etl-runners/participants-migration/`
- `etl-runners/split-allconcepts/`
- `src/.../jobs/migration/ParticipantsMigrationJob.java` (and its tests)
- `src/.../jobs/migration/SplitAllConceptsJob.java` (and its tests)
- The `etl.pipelines.migrate-all` entry in `application.yml`
- The migration job flags in `application.yml`

## Table of Contents

- [Pipeline DAG](#pipeline-dag)
- [S3 Layout](#s3-layout)
- [Pre-flight Only](#pre-flight-only)
- [Stage 1: Build and Test](#stage-1-build-and-test)
- [Stage 2: Start Participant DB](#stage-2-start-participant-db)
- [Stage 3: Migrate Participants](#stage-3-migrate-participants)
- [Stage 4: Split AllConcepts](#stage-4-split-allconcepts)
- [Stage 5: Generate Global AllConcepts](#stage-5-generate-global-allconcepts)
- [Stage 6: Create VCF Indexes](#stage-6-create-vcf-indexes)
- [Stage 7: Merge AllConcepts](#stage-7-merge-allconcepts)
- [Stage 8: AllConcepts Manifest](#stage-8-allconcepts-manifest)
- [Post: Stop Participant DB](#post-stop-participant-db)
- [Data Flow Summary](#data-flow-summary)
- [Parameters](#parameters)
- [Exit Codes](#exit-codes)
- [Local Execution](#local-execution)

---

## Pipeline DAG

```
Build ▸ Tests
      │
      ▼
┌─────────────────────────┐
│  Start participant DB    │  participant-db-start: restore participant_db.tar from S3
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│  Migrate participants    │  (all ready studies, one run)
│  participants-migration  │
└────────────┬────────────┘
             │  produces {studyid}_hpds_id_mapping.csv per study,
             │  uploaded by the orchestrator to {DATA_ROOT}/{study_id}/mappings/<build-tag>/
             ▼
┌─────────────────────────┐
│  Split AllConcepts       │  (one runner per study, SPLIT_CONCURRENCY at a time)
│  split-allconcepts       │
└────────────┬────────────┘
             │  produces {study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv per consent
             ▼
┌─────────────────────────┐
│  Generate global         │  (all ready studies, one run)
│  AllConcepts             │
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│  Create VCF indexes      │  (genomic studies only, one run)
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│  Merge AllConcepts       │  (consent folders of ready studies whose sources changed)
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│  AllConcepts manifest    │  verifies every ready consent group, then writes
│                          │  allConcepts_manifest.csv at the merge input root (versioned)
└─────────────────────────┘

post { always }:  participant-db-backup  (pg_dump to S3, DB stays up; participant_db.tar overwritten only on SUCCESS/UNSTABLE)
```

Stage ordering is strict: each stage runs only if the previous stage succeeded (UNSTABLE
continues). `split-allconcepts` depends on the mapping files from `participants-migration`.
The global AllConcepts and VCF index stages read the tables populated by
`participants-migration`. The participant database exists only for the run; see
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

`STUDY_FILTER` limits the participants and split stages to the listed studies; the global
AllConcepts and VCF stages always cover all ready studies from the database, so a filtered
rerun still ends with complete artifacts.

The first four jobs can also be run locally via `--pipeline=migrate-all`, which uses the
in-process `PipelineRunner` defined in `application.yml`.

---

## S3 Layout

Everything per-study lives in one folder per study id under `DATA_ROOT` (currently
`s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/BAM_testing`; eventually the bucket
prefix itself):

```
{DATA_ROOT}/
└── {study_id}/
    ├── legacy/
    │   ├── allConcepts/{study_id}_allConcepts_new_search_with_data_analyzer.csv   split input
    │   └── data/{ABV}_PatientMapping.v2.csv                   participants-migration input
    ├── rawData/sstr_{study_id}.{v}.txt                        SSTR (preferred source)
    ├── rawData/{study_id}*Subject.MULTI.txt                   MULTI fallback (+ *Sample.MULTI.txt,
    │                                                          *Subject.data_dict.xml, optional)
    ├── mappings/<build-tag>/
    │   └── {study_id}_hpds_id_mapping.csv                     participants-migration → split
    └── allConcepts/c{code}/
        ├── {study_id}_allConcepts_c{code}.csv                 split / generator output
        └── {study_id}_c{code}_allConcepts_MERGED.csv          merge output
```

The global AllConcepts and VCF index outputs are not per-study and keep their own prefixes.

---

## Pre-flight Only

Same behaviour as the permanent pipeline: `PREFLIGHT_ONLY=true` starts no database, triggers
`participants-migration`, `split-allconcepts`, and `create-vcf-indexes` in their pre-flight
mode, and skips `Generate global AllConcepts` and `Merge AllConcepts`. Mapping URIs are
computed but nothing is uploaded, so the split runner's mapping check is a soft warning.

---

## Stage 1: Build and Test

```
./mvnw clean package -DskipTests
./mvnw verify                       (or ./mvnw test if RUN_INTEGRATION_TESTS is off)
```

Same gate as the permanent pipeline: the full test suite runs once here, and downstream
jobs skip tests.

---

## Stage 2: Start Participant DB

**Job:** `participant-db-start`. Makes sure the long-lived database container on the Jenkins host
is up, and saves its connection details on the Jenkins server for the runners. A clean running
database is kept. `OVERWRITE_DB` replaces it from `DB_RESTORE_FROM` (blank = the latest clean
backup), and after a failed run `IF_LAST_RUN_FAILED` decides between restoring the clean backup
and continuing. See [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md), including where the initial seed
dump of the legacy RDS database must be placed.

---

## Stage 3: Migrate Participants

**Job:** `participants-migration`
**Class:** [`ParticipantsMigrationJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/ParticipantsMigrationJob.java)
**Runs:** once, processing all ready studies (or `STUDY_FILTER`) internally

### Input

| File | Location | Description |
|------|----------|-------------|
| Managed inputs CSV | `--managed-inputs` | Study master list with readiness flags |
| `{ABV}_PatientMapping.v2.csv` | `{DATA_FOLDER}/{study_id}/legacy/data/` | Per-study mapping (headerless: id, abv, legacy HPDS id). Required |
| `sstr_{studyid}.{v}.txt` | `{DATA_FOLDER}/{study_id}/rawData/` | Per-study SSTR: the preferred participant source. Matched case-insensitively; legacy `SSTR__sstr_*` / `BDC-ingestion-only__sstr_*` names accepted, canonical preferred |
| `{studyid}*Subject.MULTI.txt` | `{DATA_FOLDER}/{study_id}/rawData/` | dbGaP subject table: the fallback source when there is no SSTR, or the SSTR is malformed. `#` preamble, then tab-delimited; `dbGaP_Subject_ID` plus a numeric consent column named `consent`, `consent_1217`, `consentcol` or `gencons` (any case) |
| `{studyid}*Sample.MULTI.txt` | `{DATA_FOLDER}/{study_id}/rawData/` | dbGaP sample table (optional, fallback only): `dbGaP_Subject_ID`, `dbGaP_Sample_ID`, usually `SAMPLE_ID` |
| `{studyid}*Subject.data_dict.xml` | `{DATA_FOLDER}/{study_id}/rawData/` | dbGaP data dictionary (optional, fallback only): decodes the numeric consent codes |

All rawData names are matched case-insensitively. When several MULTI files or dictionaries match,
the newest release (highest version numbers) is used and the others are reported; a dictionary
sharing the Subject.MULTI's table accession (`pht######`) is preferred.

### Flow

```
Read managed inputs ─▶ filter to ready studies
        │
        ▼
For each ready study:
        │
        ├─── SSTR file? ── Yes ──▶ SstrPopulateRdsParticipantsJob (sub-job)
        │                              │
        │                              ├── succeeds ──────────────────────────▶ source = sstr
        │                              ├── data, validation or config error ("malformed";
        │                              │   its transaction rolls back) ─▶ fall through ▼
        │                              └── any other error (infrastructure, unknown) ─▶ abort the run
        │
        ├─── Subject.MULTI? ── Yes ──▶ MultiPopulateRdsParticipantsJob (sub-job)
        │                              │
        │                              ├── succeeds ──────────────────────────▶ source = multi
        │                              ├── data, validation or config error ─▶ study FAILS (MULTI_LOAD_FAILED)
        │                              └── any other error (infrastructure, unknown) ─▶ abort the run
        │
        └─── neither ──▶ study FAILS (NO_PARTICIPANT_SOURCE)
        │
        ▼
Read {ABV}_PatientMapping.v2.csv; join its id against the source's dbGaP subject id
or the study's own subject id (SSTR SUBJECT_ID / MULTI SUBJECT_ID or SUBJID)
        │
        ▼
Write {studyid}_hpds_id_mapping.csv, then (once, for the run) the source and issue reports
```

The MULTI sub-job writes the same rows the SSTR job would:

- one `participants` row per subject with a usable consent, keyed by `dbGaP_Subject_ID` with
  source `DBGap`. Consent `0` (not consented) subjects are dropped;
- one `consents` row per participant: the numeric code, and as the abbreviation the text in the
  trailing parentheses of its data-dictionary label (`General Research Use (GRU)` → `GRU`), the
  full label if it has none, or blank when there is no dictionary or it doesn't decode that code;
- from the Sample.MULTI, a `DBGap` sample per `dbGaP_Sample_ID` and a `submitted` sample per
  `SAMPLE_ID`, for loaded subjects only. No Sample.MULTI, or an unreadable one, loads no samples.

Like the SSTR job it purges the study's `consents` first, in the same transaction as the load.
It is migration-only (`JobType.MIGRATION`); the permanent pipeline never reads MULTI files.

Studies are processed independently. A data failure in one study does not stop the rest. A
sub-job failure that is not about the study's data aborts the run: an infrastructure error with
exit 4 (retried), anything unclassified (e.g. a database that can't open a transaction) with exit 1.
The MULTI job requires a `phs######` study id, as the SSTR job does. The job exits with `VALIDATION_FAILED` if any
study failed while others succeeded, and `SUCCESS_WITH_WARNINGS` if a study fell back from a
malformed SSTR or its MULTI files had problems.

### Output

- Populated participant database tables: `participants`, `consents`, `samples`
- One `{studyid}_hpds_id_mapping.csv` per succeeded study with columns:
  - `old_hpds_id` -- the legacy integer HPDS id
  - `new_hpds_id` -- the new integer HPDS id (from `hpds_id_seq`)
  - `common_dbgap_id` -- the dbGaP subject id
- `participants_migration_sources.csv`: one row per ready study -- outcome, source used
  (`sstr`/`multi`/`none`), the SSTR/MULTI/dictionary files found, whether the SSTR was used or
  malformed, the consent column read, mapping and unresolved row counts, and the error if it failed
- `participants_migration_file_issues.csv`: every problem found in those files, one row per
  study and issue code, with severity, file, count and the first few examples
- `participants_migration_file_report.md`: both of the above for a person to read, studies
  grouped by source and outcome
- Metrics `sstrStudyIds`, `multiStudyIds`, `sstrFallbackStudyIds`, `noParticipantSourceStudyIds`

Issue codes in the file issues report:

| Code | Severity | Meaning |
|------|----------|---------|
| `SSTR_MALFORMED` | warning | The SSTR sub-job failed on its data; the study fell back to MULTI |
| `NO_PARTICIPANT_SOURCE` | error | No usable SSTR and no Subject.MULTI; the study failed |
| `MULTI_LOAD_FAILED` | error | The MULTI sub-job failed (e.g. no consent column, no consented subject); the study failed |
| `MULTIPLE_SSTR_CANDIDATES`, `MULTIPLE_SUBJECT_MULTI_CANDIDATES`, `MULTIPLE_SAMPLE_MULTI_CANDIDATES`, `MULTIPLE_DATA_DICT_CANDIDATES` | warning | Several files matched; the one used is named |
| `MULTIPLE_CONSENT_COLUMNS` | warning | Several known consent headers; the first of `consent`, `consent_1217`, `consentcol`, `gencons` was used |
| `CONSENT_ZERO_DROPPED` | info | Consent-0 subjects, dropped by design |
| `BLANK_CONSENT`, `NON_NUMERIC_CONSENT`, `BLANK_DBGAP_SUBJECT_ID`, `CONFLICTING_CONSENT` | warning | Subject rows skipped (or, for a conflict, the first consent kept) |
| `NO_SUBJECT_ID_COLUMN` | info | No `SUBJECT_ID`/`SUBJID`; patient mapping ids can only match the dbGaP id |
| `NO_SAMPLE_MULTI` | info | No Sample.MULTI; no samples loaded |
| `SAMPLE_MULTI_UNREADABLE`, `NO_SAMPLE_ROWS` | warning | Sample.MULTI unusable or empty; no samples loaded |
| `NO_SUBMITTED_SAMPLE_ID_COLUMN` | info | Only dbGaP sample ids loaded |
| `BLANK_DBGAP_SAMPLE_ID`, `BLANK_SAMPLE_SUBJECT_ID` | warning | Sample rows with missing ids |
| `SAMPLES_OF_UNCONSENTED_SUBJECTS` | info | Samples of consent-0 subjects, not loaded |
| `SAMPLES_OF_UNKNOWN_SUBJECTS` | warning | Samples whose subject has no usable consent in the Subject.MULTI, not loaded |
| `NO_DATA_DICT`, `DATA_DICT_UNREADABLE`, `CONSENT_VARIABLE_NOT_IN_DATA_DICT` | warning | Abbreviations left blank |
| `CONSENT_CODE_NOT_DECODED` | warning | Codes the dictionary doesn't decode; their abbreviations are blank |
| `CONSENT_LABEL_WITHOUT_ABBREVIATION` | warning | Labels with no trailing `(ABBREV)`; the full label was used |
| `PATIENT_MAPPING_NOT_CONSENTED` | info | Patient mapping rows naming a consent-0 subject; not mapped |
| `PATIENT_MAPPING_UNRESOLVED` | warning | Patient mapping rows matching no loaded subject; not mapped |

The orchestrator copies the mapping files from the runner's artifacts and uploads them, in one
recursive copy, to `{DATA_ROOT}/{study_id}/mappings/<build-tag>/` for the split stage — the split container cannot
see the orchestrator's workspace. The source and issue reports stay with the runner's archived
artifacts.

---

## Stage 4: Split AllConcepts

**Job:** `split-allconcepts`
**Class:** [`SplitAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/SplitAllConceptsJob.java)
**Runs:** once per ready study, **in parallel**, at most `SPLIT_CONCURRENCY` (default 5) at a
time (one runner each; the job only reads the database). A fixed pool of that many workers takes
the next study as each finishes, so 600 ready studies still mean at most 5 split builds and 5 EC2
runners at once.

**Runner image:** the orchestrator builds the runner image once, at the start of this stage, and
passes it to every split build as `PREBUILT_IMAGE_TAR`; those builds skip their own JAR build and
image packaging. Without this, each study would compile the JAR and build, save and upload the same
image on the Jenkins host. The orchestrator deletes the image (S3 tarball, local tarball, and the
host's podman image) in `post { always }`. Run standalone, `split-allconcepts` still builds its own.

### Input

| File | Source | Description |
|------|--------|-------------|
| Legacy allConcepts CSV | `{DATA_ROOT}/{study_id}/legacy/allConcepts/{study_id}_allConcepts_new_search_with_data_analyzer.csv` | The study's unified allConcepts file |
| `{studyid}_hpds_id_mapping.csv` | `{DATA_ROOT}/{study_id}/mappings/<build-tag>/` | Maps legacy ids to new HPDS ids |
| `consents` table | Participant database | Consent assignments for the study |

### Flow

```
Read hpds_id_mapping.csv ─▶ build lookup: legacy HPDS id → new hpds_id
        │
        ▼
Read legacy allConcepts CSV (streaming)
        │
        ▼
For each row:
  ├─ Replace the legacy HPDS id with the new hpds_id (via mapping)
  └─ Look up the patient's consent group
        │
        ▼
Route row to the appropriate per-consent output file
        │
        ▼
Write per-consent files:
  {DATA_ROOT}/{study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv
```

### Output

Per-consent allConcepts files at
`{DATA_ROOT}/{study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv` — the same layout
`all-concepts-data-generator` uses, so the merge stage treats both alike. (Split output from
earlier runs sits under `split_allconcepts/{study_id}/c{code}/` or
`__migration__/split_allconcepts/`; copy it across if it should be merged.)

- Legacy HPDS ids replaced by new HPDS ids
- One file per consent group instead of one unified file

### Error Handling

- Rows with unmapped HPDS ids are logged as warnings and skipped
- `CONTINUE_ON_STUDY_FAILURE` (default `true`) lets the remaining studies finish when one
  fails; off, no new study is started after a failure (builds already running finish), and the
  studies never started are reported as `NOT_BUILT`
- `DATA_ROOT`, `ALL_CONCEPTS_OUTPUT`, and `VCF_INDEXES_OUTPUT` must
  be `s3://` URIs — checked before any split is scheduled

---

## Stage 5: Generate Global AllConcepts

**Job:** `generate-global-all-concepts`
**Class:** [`GenerateGlobalAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateGlobalAllConceptsJob.java)
**Runs:** once, covering all ready studies (skipped under `PREFLIGHT_ONLY`)

The same job as the permanent pipeline (see
[`PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md#stage-5-generate-global-allconcepts)),
reused here to generate the global AllConcepts from the newly migrated data. Writes
`global_AllConcepts.csv` to `ALL_CONCEPTS_OUTPUT`.

---

## Stage 6: Create VCF Indexes

**Job:** `create-vcf-indexes`
**Class:** [`CreateVCFIndexesJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/genomic/CreateVCFIndexesJob.java)
**Runs:** once, genomic studies only

The same job as the permanent pipeline (see
[`PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md#stage-6-create-vcf-indexes)), run with
`INCLUDE_PROCESSED=true`: the migration regenerates artifacts for studies the legacy system
already processed, which would otherwise be zero work. Writes
`{studyId}.c{code}_vcfIndex.tsv` and `{studyId}.c{code}_SampleIds.csv` to
`VCF_INDEXES_OUTPUT`.

---

## Stage 7: Merge AllConcepts

**Job:** `merge-allconcepts`
**Class:** [`MergeAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/MergeAllConceptsJob.java)
**Runs:** once (skipped under `PREFLIGHT_ONLY`)

For every study marked ready in `MANAGED_INPUTS`, keeps one
`{study_id}_c{code}_allConcepts_MERGED.csv` per `{study_id}/allConcepts/c{code}/` folder under
`MERGE_ALLCONCEPTS_INPUT` (blank = `DATA_ROOT`): merges a folder that has none, re-merges one whose
`*_allConcepts_*` files were added, overwritten or deleted since (detected from S3 version ids, so
the bucket must be versioned), and skips the rest. See
[`PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md#stage-8-merge-allconcepts).

---

## Stage 8: AllConcepts Manifest

**Job:** `generate-allconcepts-manifest`
**Class:** [`GenerateAllConceptsManifestJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateAllConceptsManifestJob.java)
**Runs:** once, last (skipped under `PREFLIGHT_ONLY`)

Verifies that every ready consent group's MERGED file is the merge of exactly its current source
files, and that `global_AllConcepts.csv` under `ALL_CONCEPTS_OUTPUT` exists. Then it writes the
versioned `allConcepts_manifest.csv` at the root of `MERGE_ALLCONCEPTS_INPUT` (blank =
`DATA_ROOT`): `partition, s3_uri, version_id, last_updated, md5`, with a `global` row for the
global file first and `{study_id}.c{code}` (e.g. `phs000001.c1`) for each consent group. If any check fails, no manifest is written and the build fails. See
[`PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md#stage-9-allconcepts-manifest).

---

## Post: Back Up Participant DB

**Job:** `participant-db-backup`, in `post { always }` whenever this build's start succeeded.
Dumps the database to S3 and leaves it running. The dump becomes `participant_db.tar` only if the
build is SUCCESS or UNSTABLE; otherwise it goes to `participant_db_not_promoted.tar`, and the next
start acts on `IF_LAST_RUN_FAILED`. A failed backup fails the build. See
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

---

## Data Flow Summary

```
LEGACY SYSTEM                             PARTICIPANT DATABASE
─────────────                             ────────────────────

{ABV}_PatientMapping.v2.csv ────┐
                                │
                                ├──▶ participants-migration ──▶ participants,
sstr_{studyid}.{v}.txt, or ────┤                            │  consents, samples
*Subject/Sample.MULTI.txt       │                            │
                                │                            │  (dumped to S3 at the end)
Managed inputs CSV ─────────────┘                            │
                                                             │
                                            ┌────────────────┼──────────────────┐
                                            │                │                  │
                                            ▼                ▼                  ▼
                                   {studyid}_hpds_     global_AllConcepts   VCF indexes
                                   id_mapping.csv         .csv             + SampleIds
                                            │                                  .csv/.tsv
Legacy allConcepts CSV ────────────────────┤
                                            ▼
                                   split-allconcepts
                                            │
                                            ▼
                                   {study_id}/allConcepts/c{code}/{study_id}_allConcepts_c{code}.csv
                                            │
                                            ▼
                                   merge-allconcepts ─▶ {study_id}_c{code}_allConcepts_MERGED.csv
                                            │
                                            ▼
                                   generate-allconcepts-manifest ─▶ allConcepts_manifest.csv
```

---

## Parameters

Data paths below are under `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/`
(abbreviated `…/`).

| Parameter | Default | Description |
|-----------|---------|-------------|
| `MANAGED_INPUTS` | `…/__migration__/managed_inputs.csv` | Study list CSV |
| `DATA_ROOT` | `…/BAM_testing` | Root of the per-study folders (see [S3 Layout](#s3-layout)); passed to `participants-migration` as `DATA_FOLDER`, used for the split input, mapping uploads, and split output (must be `s3://`) |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `STUDY_FILTER` | (blank) | Comma-separated study ids for the participants and split stages; blank = all ready |
| `PARTICIPANTS_MIGRATION_JOB` | `participants-migration` | Participants runner job |
| `SPLIT_ALLCONCEPTS_JOB` | `split-allconcepts` | Split runner job |
| `ALL_CONCEPTS_JOB` | `generate-global-all-concepts` | Global AllConcepts runner job |
| `ALL_CONCEPTS_OUTPUT` | `…/__migration__/global_allconcepts/` | Output for global AllConcepts (must be `s3://`) |
| `VCF_INDEXES_JOB` | `create-vcf-indexes` | VCF index runner job |
| `VCF_INDEXES_OUTPUT` | `…/__migration__/vcf_indexes/` | Output for VCF indexes (must be `s3://`) |
| `MERGE_ALLCONCEPTS_JOB` | `merge-allconcepts` | Merge runner job |
| `MERGE_ALLCONCEPTS_INPUT` | (blank) | Prefix to merge and write the manifest at; blank = `DATA_ROOT` |
| `ALLCONCEPTS_MANIFEST_JOB` | `generate-allconcepts-manifest` | Manifest runner job (last stage) |
| `DB_START_JOB` | `participant-db-start` | Job that starts the participant database |
| `DB_BACKUP_JOB` | `participant-db-backup` | Job that dumps it at the end of the run (it stays up) |
| `OVERWRITE_DB` | `false` | Replace the database before this run from `DB_RESTORE_FROM` (blank = the latest clean backup), saving a running one first (see [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md#start-rules)) |
| `DB_RESTORE_FROM` | (blank) | What a new or replaced database is loaded from: blank = latest clean backup, `none`, a file under `backups/`, or an `s3://` URI |
| `IF_LAST_RUN_FAILED` | `restore-clean` | If the previous run failed: `restore-clean` replaces its data from the latest clean backup; `continue` builds on it |
| `SPLIT_CONCURRENCY` | `5` | Most split builds (and EC2 runners) at once; the other studies wait for a free slot |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep splitting remaining studies when one fails |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `PREFLIGHT_ONLY` | `false` | Validate inputs and stop without provisioning (see [Pre-flight Only](#pre-flight-only)) |
| `ENV` | `development` | Selects `etl-runners/environments/<ENV>.tfvars` |

Job-name parameters resolve relative to the `hpds-etl` Jenkins folder.

---

## Exit Codes

| Code | Name | Meaning |
|-----:|------|---------|
| 0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | Migration completed; warnings mark the build UNSTABLE |
| 1 | `UNKNOWN` | Unhandled failure |
| 2 | `VALIDATION_FAILED` | Some studies failed while others succeeded |
| 3 | `DATA_ERROR` | Data-level failure |
| 4 | `INFRASTRUCTURE_ERROR` | Retryable infrastructure issue |
| 5 | `CONFIG_ERROR` | Missing parameter, disabled job, or participant database not running |

---

## Local Execution

The migration jobs can be run locally using the in-process pipeline runner, against any
reachable Postgres (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`):

```bash
java -jar target/hpds-etl.jar \
  --pipeline=migrate-all \
  --managed-inputs=/path/to/managed_inputs.csv \
  --data-folder=/path/to/migration/data \
  --output=/path/to/output \
  --batch-size=1000
```

This uses `PipelineRunner` to execute `participants-migration`, `split-allconcepts`,
`generate-global-all-concepts`, and `create-vcf-indexes` sequentially, stopping at the
first failure. (`merge-allconcepts` is not part of `migrate-all`.)

---

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) -- participant database lifecycle and dumps
- [`docs/PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md) -- the ongoing ingestion pipeline
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags and
  pipeline definition
