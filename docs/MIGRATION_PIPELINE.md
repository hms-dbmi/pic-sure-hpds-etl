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
- [Pre-flight Only](#pre-flight-only)
- [Stage 1: Build and Test](#stage-1-build-and-test)
- [Stage 2: Start Participant DB](#stage-2-start-participant-db)
- [Stage 3: Migrate Participants](#stage-3-migrate-participants)
- [Stage 4: Split AllConcepts](#stage-4-split-allconcepts)
- [Stage 5: Generate Global AllConcepts](#stage-5-generate-global-allconcepts)
- [Stage 6: Create VCF Indexes](#stage-6-create-vcf-indexes)
- [Stage 7: Merge AllConcepts](#stage-7-merge-allconcepts)
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
│  Start participant DB    │  participant-db-start: restore LATEST dump from S3
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│  Migrate participants    │  (all ready studies, one run)
│  participants-migration  │
└────────────┬────────────┘
             │  produces {studyid}_hpds_id_mapping.csv per study,
             │  uploaded by the orchestrator to MAPPING_UPLOAD_BASE/<build-tag>/
             ▼
┌─────────────────────────┐
│  Split AllConcepts       │  (one runner per study, in parallel)
│  split-allconcepts       │
└────────────┬────────────┘
             │  produces {study_id}/c{code}/{study_id}_allConcepts_c{code}.csv per consent
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
│  Merge AllConcepts       │  (consent folders under SPLIT_OUTPUT needing a merge)
└─────────────────────────┘

post { always }:  participant-db-stop  (pg_dump to S3; LATEST promoted only on SUCCESS/UNSTABLE)
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

**Job:** `participant-db-start`. Restores the dump named by `backups/LATEST` (or a fresh schema
from `schema.sql`) and publishes the connection secret. Fails if a database is already running
in the environment. See [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md), including where the initial
seed dump of the legacy RDS database must be placed.

---

## Stage 3: Migrate Participants

**Job:** `participants-migration`
**Class:** [`ParticipantsMigrationJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/ParticipantsMigrationJob.java)
**Runs:** once, processing all ready studies (or `STUDY_FILTER`) internally

### Input

| File | Location | Description |
|------|----------|-------------|
| Managed inputs CSV | `--managed-inputs` | Study master list with readiness flags |
| `GLOBAL_allConcepts_merged.csv` | `{DATA_FOLDER}/general/completed/` | Legacy file (headerless, all-quoted) with consent codes and abbreviations per legacy HPDS id |
| `{ABV}_PatientMapping.v2.csv` | `{DATA_FOLDER}/{abv_lower}/data/` | Per-study mapping (headerless: id, abv, legacy HPDS id) |
| `sstr_{studyid}.{v}.txt` | `{DATA_FOLDER}/{abv_lower}/rawData/` | Per-study SSTR (optional; determines processing path). Matched case-insensitively; legacy `SSTR__sstr_*` / `BDC-ingestion-only__sstr_*` names accepted, canonical preferred |

### Flow

```
Read managed inputs ─▶ filter to ready studies
Read GLOBAL_allConcepts_merged.csv ─▶ build consent lookup (legacy HPDS id → consent info)
        │
        ▼
For each ready study:
        │
        ├─── SSTR file exists? ──── Yes ──▶ SSTR path
        │                                       │
        │                                       ▼
        │                           Delegate to SstrPopulateRdsParticipantsJob
        │                           (runs as a sub-job via JobExecutor)
        │                                       │
        │                                       ▼
        │                           Read {ABV}_PatientMapping.v2.csv
        │                           Join its id against the SSTR's
        │                           SUBJECT_ID / dbgap_subject_id
        │                                       │
        │                                       ▼
        │                           Build mapping: legacy id → new hpds_id → dbGaP id
        │
        └─── SSTR file exists? ──── No ───▶ Direct path
                                                │
                                                ▼
                                    Read {ABV}_PatientMapping.v2.csv
                                    Join against GLOBAL_allConcepts_merged.csv
                                    for consent codes
                                                │
                                                ▼
                                    Upsert participants (source = study_id)
                                    Upsert consents
                                    (all in one transaction per study)
                                                │
                                                ▼
                                    Build mapping: legacy id → new hpds_id → source id
        │
        ▼
Write {studyid}_hpds_id_mapping.csv to the reports directory
```

Studies are processed independently. A data failure in one study does not stop the rest
(an infrastructure failure aborts the run). The job exits with `VALIDATION_FAILED` if any
study failed while others succeeded.

### Output

- Populated participant database tables: `participants`, `consents`, `samples`
- One `{studyid}_hpds_id_mapping.csv` per study with columns:
  - `old_hpds_id` -- the legacy integer HPDS id
  - `new_hpds_id` -- the new integer HPDS id (from `hpds_id_seq`)
  - `common_dbgap_id` -- the dbGaP subject id (or the patient mapping id for non-SSTR studies)

The orchestrator copies these from the runner's artifacts and uploads them to
`{MAPPING_UPLOAD_BASE}/<build-tag>/` for the split stage — the split container cannot see the
orchestrator's workspace.

### Special Cases

- **`open_access-1000Genomes`**: handled on the direct path with sample rows written
  (subject IDs are also sample IDs for this dataset)

---

## Stage 4: Split AllConcepts

**Job:** `split-allconcepts`
**Class:** [`SplitAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/SplitAllConceptsJob.java)
**Runs:** once per ready study, **in parallel** (one runner each; the job only reads the
database)

### Input

| File | Source | Description |
|------|--------|-------------|
| Legacy allConcepts CSV | `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/{abv_lower}/completed/{study_id}/{study_id}_allConcepts_new_search_with_data_analyzer.csv` | The study's unified allConcepts file |
| `{studyid}_hpds_id_mapping.csv` | `{MAPPING_UPLOAD_BASE}/<build-tag>/` | Maps legacy ids to new HPDS ids |
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
  {SPLIT_OUTPUT}/{study_id}/c{code}/{study_id}_allConcepts_c{code}.csv
```

### Output

Per-consent allConcepts files at
`{SPLIT_OUTPUT}/{study_id}/c{code}/{study_id}_allConcepts_c{code}.csv` — the same layout
`all-concepts-data-generator` uses, so the merge stage treats both alike.

`SPLIT_OUTPUT` defaults to the shared `…/split_allconcepts/` prefix, not a `__migration__/`
one: it is where every pipeline's per-consent allConcepts files land, so the permanent
pipeline's merge picks up the migrated studies alongside its own. (Split output from runs
before this change sits under `__migration__/split_allconcepts/`; copy it across if it should
be merged.)

- Legacy HPDS ids replaced by new HPDS ids
- One file per consent group instead of one unified file

### Error Handling

- Rows with unmapped HPDS ids are logged as warnings and skipped
- `CONTINUE_ON_STUDY_FAILURE` (default `true`) lets the remaining studies finish when one
  fails; off, the parallel branches fail fast
- `SPLIT_OUTPUT`, `MAPPING_UPLOAD_BASE`, `ALL_CONCEPTS_OUTPUT`, and `VCF_INDEXES_OUTPUT` must
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

Scans `MERGE_ALLCONCEPTS_INPUT` (blank = `SPLIT_OUTPUT`) recursively and, for every
`{study_id}/c{code}/` folder whose `{study_id}_c{code}_allConcepts_MERGED.csv` is missing,
older than a source file, or older than a source deletion, concatenates the folder's
allConcepts files into it. See
[`PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md#stage-7-merge-allconcepts).

---

## Post: Stop Participant DB

**Job:** `participant-db-stop`, in `post { always }` whenever this build started the database.
Dumps it to S3 and destroys it; the dump becomes `LATEST` only if the build is SUCCESS or
UNSTABLE. A failed stop fails the build and leaves the database running. See
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

---

## Data Flow Summary

```
LEGACY SYSTEM                             PARTICIPANT DATABASE
─────────────                             ────────────────────

GLOBAL_allConcepts_merged.csv ──┐
                                │
{ABV}_PatientMapping.v2.csv ────┤
                                ├──▶ participants-migration ──▶ participants,
sstr_{studyid}.{v}.txt ─────────┤                            │  consents, samples
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
                                   {study_id}/c{code}/{study_id}_allConcepts_c{code}.csv
                                            │
                                            ▼
                                   merge-allconcepts ─▶ {study_id}_c{code}_allConcepts_MERGED.csv
```

---

## Parameters

Data paths below are under `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/`
(abbreviated `…/`).

| Parameter | Default | Description |
|-----------|---------|-------------|
| `MANAGED_INPUTS` | `…/__migration__/managed_inputs.csv` | Study list CSV |
| `DATA_FOLDER` | `…/__migration__/current` | Base of the legacy export (`general/completed/`, `{abv_lower}/`, `{abv_lower}/rawData/`) |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `STUDY_FILTER` | (blank) | Comma-separated study ids for the participants and split stages; blank = all ready |
| `PARTICIPANTS_MIGRATION_JOB` | `participants-migration` | Participants runner job |
| `SPLIT_ALLCONCEPTS_JOB` | `split-allconcepts` | Split runner job |
| `MAPPING_UPLOAD_BASE` | `…/__migration__/mappings` | Where mapping CSVs are uploaded (a per-build tag is appended) |
| `SPLIT_OUTPUT` | `…/split_allconcepts/` | Output for split allConcepts files (must be `s3://`). Deliberately not under `__migration__/`: it is the shared per-consent allConcepts location the permanent pipeline also writes to and merges from |
| `ALL_CONCEPTS_JOB` | `generate-global-all-concepts` | Global AllConcepts runner job |
| `ALL_CONCEPTS_OUTPUT` | `…/__migration__/global_allconcepts/` | Output for global AllConcepts (must be `s3://`) |
| `VCF_INDEXES_JOB` | `create-vcf-indexes` | VCF index runner job |
| `VCF_INDEXES_OUTPUT` | `…/__migration__/vcf_indexes/` | Output for VCF indexes (must be `s3://`) |
| `MERGE_ALLCONCEPTS_JOB` | `merge-allconcepts` | Merge runner job |
| `MERGE_ALLCONCEPTS_INPUT` | (blank) | Prefix to merge; blank = `SPLIT_OUTPUT` |
| `DB_START_JOB` | `participant-db-start` | Job that starts the participant database |
| `DB_STOP_JOB` | `participant-db-stop` | Job that dumps and stops it |
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
