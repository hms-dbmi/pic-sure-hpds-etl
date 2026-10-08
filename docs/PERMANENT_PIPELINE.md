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
- [Stage 3: Start Participant DB](#stage-3-start-participant-db)
- [Stage 4: Load SSTR Participants](#stage-4-load-sstr-participants)
- [Stage 5: Generate Global AllConcepts](#stage-5-generate-global-allconcepts)
- [Stage 6: Create VCF Indexes](#stage-6-create-vcf-indexes)
- [Stage 7: Generate Per-Study AllConcepts](#stage-7-generate-per-study-allconcepts)
- [Stage 8: Merge AllConcepts](#stage-8-merge-allconcepts)
- [Post: Stop Participant DB](#post-stop-participant-db)
- [Data Flow Summary](#data-flow-summary)
- [Parameters](#parameters)
- [Exit Codes](#exit-codes)

---

## Pipeline DAG

```
Build ▸ Tests ▸ Resolve studies
                      │
                      ▼
          ┌───────────────────────┐
          │  Start participant DB │  participant-db-start: restore LATEST dump from S3
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
          │  Generate per-study   │  (per unprocessed study; sequential by default)
          │  AllConcepts          │  any failure halts here, before the merge
          │  all-concepts-data-   │
          │  generator            │
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  Merge AllConcepts    │  (consent folders needing a merge)
          └───────────────────────┘

post { always }:  participant-db-stop  (pg_dump to S3; LATEST promoted only on SUCCESS/UNSTABLE)
```

Each stage runs only if the previous stage succeeded. The participant database exists only
between `Start participant DB` and the post-build stop; see
[`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) for its lifecycle, dump location, and recovery.

Per-study allConcepts runs after VCF rather than straight after the SSTR load: it needs only
the study's participants and consents, and placing it last means a generation failure stops
just the merge, not the global AllConcepts and VCF outputs.

---

## Trigger Modes

| Mode | `STUDY_ID` | Behavior |
|------|------------|----------|
| **Sweep** | blank | Loads every study marked "Data is ready to process" = Yes in managed inputs. Skips studies already marked "Data Processed". |
| **Single study** | `phs######` | Loads exactly that study (reload or manual entry); the ready/processed flags do not apply. `INPUT` overrides the discovered SSTR URI. |

`MANAGED_INPUTS` is **required in both modes**: the study list (and, for a single study, its
abbreviation, which locates its SSTR) comes from it, and the global AllConcepts and VCF jobs
read it.

---

## Pre-flight Only

`PREFLIGHT_ONLY=true` validates inputs without provisioning anything:

- the participant database is **not** started (and so not stopped);
- `sstr-populate-rds-participants`, `create-vcf-indexes`, and `all-concepts-data-generator`
  are triggered with `PREFLIGHT_ONLY=true`, running only their agent-side checks;
- `Generate global AllConcepts` and `Merge AllConcepts` are **skipped**: they have no
  pre-flight mode, and the first needs the database.

---

## Stage 1: Build and Test

```
./mvnw clean package -DskipTests
./mvnw verify                       (or ./mvnw test if RUN_INTEGRATION_TESTS is off)
```

Builds the fat JAR and runs the test suites. This is the gate: nothing is provisioned or
loaded until the suites pass. Downstream jobs skip tests (`SKIP_TESTS=true`) to avoid
re-running the same commit's suites per study.

---

## Stage 2: Resolve Studies

Reads the managed inputs CSV (from S3 or local) to build the list of studies to process, and
checks every `s3://` output parameter before anything is provisioned.

**In sweep mode:**
1. Parses the managed inputs CSV for columns: `Study Abbreviated Name`, `Study Identifier`,
   `Data is ready to process`, `Data Processed`.
2. Selects studies where `Data is ready to process = Yes`.
3. Splits into unprocessed (will run SSTR load, VCF indexes, and per-study allConcepts) and
   already-processed (skipped for all three, still included in global AllConcepts
   regeneration).
4. Validates all study IDs match `phs######` and checks for duplicates.
5. Discovers each unprocessed study's SSTR and checks its allConcepts inputs (below).

**In single-study mode:**
1. Uses `STUDY_ID` directly. Its abbreviation is looked up in managed inputs; if the study is
   not listed there, `INPUT` is required.
2. `INPUT`, when set, is used as-is; otherwise the SSTR is discovered.

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

If any unprocessed study lacks its SSTR or either allConcepts input, the stage fails listing
every such study — before the database is started or any runner is provisioned.

---

## Stage 3: Start Participant DB

**Job:** `participant-db-start` ([`etl-runners/participant-db/Jenkinsfile.start`](../etl-runners/participant-db/Jenkinsfile.start))

Brings up the Postgres server, restoring the dump named by `backups/LATEST` (or a fresh
schema when there is none), and publishes its connection details to a temporary secret the
runners read. Fails if a database is already running in the environment — another pipeline is
using it, or a previous stop failed. Only a database this build started is stopped by this
build. Skipped under `PREFLIGHT_ONLY`. Details: [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

---

## Stage 4: Load SSTR Participants

**Job:** `sstr-populate-rds-participants`
**Class:** [`SstrPopulateRdsParticipantsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/participants/SstrPopulateRdsParticipantsJob.java)
**Runs:** once per unprocessed study — **sequentially** by default; `PARALLEL_STUDY_LOADS=true`
loads them concurrently (correct, see Concurrency in [`JENKINS.md`](JENKINS.md)).
With `CONTINUE_ON_STUDY_FAILURE=false`, a sequential sweep stops at the first failed study.

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
with row/insert counts.

### Alternative: Single-Consent Studies

Studies without an SSTR file use `single-consent-data-populate-rds-participants` instead.
This job reads a simple CSV of subject IDs and applies a uniform consent (either GRU for
"single" consent type or blank for "public"). It has no runner or orchestrator stage yet.

---

## Stage 5: Generate Global AllConcepts

**Job:** `generate-global-all-concepts`
**Class:** [`GenerateGlobalAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateGlobalAllConceptsJob.java)
**Runs:** once, covering all ready studies (skipped under `PREFLIGHT_ONLY`)

### Input

- Managed inputs CSV (`MANAGED_INPUTS`, to discover all ready studies)
- Participant database tables: `participants`, `consents`, `samples` (populated by Stage 4)

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

## Stage 6: Create VCF Indexes

**Job:** `create-vcf-indexes`
**Class:** [`CreateVCFIndexesJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/genomic/CreateVCFIndexesJob.java)
**Runs:** once, only if there are unprocessed studies. Receives `MANAGED_INPUTS` and
`PREFLIGHT_ONLY`. An UNSTABLE result marks the build UNSTABLE and continues; anything worse
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

---

## Stage 7: Generate Per-Study AllConcepts

**Job:** `all-concepts-data-generator`
**Class:** [`AllConceptsDataGeneratorJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/AllConceptsDataGeneratorJob.java)
**Runs:** once per unprocessed study — sequentially by default, concurrently with
`PARALLEL_STUDY_LOADS=true`. Receives `PREFLIGHT_ONLY` and `SKIP_ANALYSIS`.

### Input

- the study's decoded data CSVs and concept mapping ([derived in Resolve studies](#per-study-allconcepts-inputs))
- participant database tables `participants` and `consents`, populated for the study by Stage 4

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
Delete this job's own file in any other allConcepts/c{code}/ folder of the study (a group that produced
no rows this run, or that a reload removed)
```

### Output

Per-consent allConcepts files under `{DATA_ROOT}/{study_id}/allConcepts/` — the same folders
the migration's `split-allconcepts` writes to, and which other sources will write into too.

- **Overwrite, not append.** Each run replaces the study's files in place; S3 versioning keeps
  every earlier version (including a migrated split file it replaces). The runner's pre-flight
  **refuses an output bucket without versioning Enabled**.
- **Stale groups are removed, others' files are not.** Only `{study_id}_allConcepts_c{code}.csv`
  — this job's own file name — is ever deleted; a delete on a versioned bucket is a recoverable
  marker, and it is what makes the merge rebuild that folder (`DELETIONS`). Each removal is a
  `STALE_OUTPUT_REMOVED` warning (build UNSTABLE).
- The runner's `validate.sh` checks the report, that there is one file per consent group with
  rows, and that every listed file is really in S3.

### Failure

The stage runs every study (unless `CONTINUE_ON_STUDY_FAILURE` is off), prints a per-study
summary, and **fails the build before Merge AllConcepts** if any study failed. A merged file
built while a study is missing or half-written would silently lack its rows. Fix the study
and re-run it with `STUDY_ID`; its files are simply overwritten.

---

## Stage 8: Merge AllConcepts

**Job:** `merge-allconcepts`
**Class:** [`MergeAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/MergeAllConceptsJob.java)
**Runs:** once (skipped under `PREFLIGHT_ONLY`). Touches no database.

### Input

`DATA_ROOT`: the `s3://` root (versioned bucket) of the per-study folders. Only
`{study_id}/allConcepts/c{code}/` folders are scanned — never `{study_id}/legacy/allConcepts/` or
the rest of the study folder — for `*_allConcepts_*` files from every source: Stage 7's
generator, the migration's `split-allconcepts`, and further sources to come. The generator's
output and the merge's input are one parameter, so they cannot drift apart.

### Flow

```
List the phs###### study folders under the input prefix (or take --study-ids)
        │
        ▼
List each study's allConcepts/ folder; group *_allConcepts_* files directly inside
an allConcepts/c{code}/ folder by that folder (ignoring existing *_MERGED.csv)
        │
        ▼
For each folder, decide whether {study}_c{code}_allConcepts_MERGED.csv needs rebuilding:
  MISSING     no merged file yet
  STALE       a source file is newer than the merged file
  DELETIONS   a source file has a delete marker newer than the merged file
  NONE        up to date ─▶ skipped
        │
        ▼
Concatenate the folder's source files into the merged file
```

### Output

One `{study_id}_c{code}_allConcepts_MERGED.csv` per consent folder that needed merging, and a
report with `consentFoldersDiscovered`, `foldersMerged`, `foldersSkipped`, and the reason per
folder.

Runs only if Stage 7 succeeded (or was skipped because no study is unprocessed).

---

## Post: Stop Participant DB

**Job:** `participant-db-stop` ([`etl-runners/participant-db/Jenkinsfile.stop`](../etl-runners/participant-db/Jenkinsfile.stop))

Runs in `post { always }` whenever this build started the database. It `pg_dump`s the
database to S3, verifies the upload, and destroys the server and its secret. The dump becomes
the next run's restore point (`LATEST`) only if the build result so far is SUCCESS or
UNSTABLE; a failed or aborted run is still dumped, but `LATEST` stays on the last good dump.
If the stop fails, the build fails and the database is left running with its writes intact —
re-run `participant-db-stop`. See [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

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
| `MANAGED_INPUTS` | (blank) | **Required.** Managed inputs CSV URI |
| `DATA_ROOT` | `…/BAM_testing` | Root of the per-study folders, one per study id (must be `s3://`, versioned bucket). SSTRs are discovered in `{DATA_ROOT}/{study_id}/rawData/`; the generator writes `{study_id}/allConcepts/c{code}/` files there and the merge reads them; shared with the migration's `DATA_ROOT` |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `SSTR_JOB` | `sstr-populate-rds-participants` | SSTR runner job |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep loading remaining studies when one fails |
| `PARALLEL_STUDY_LOADS` | `false` | Load studies concurrently instead of one at a time |
| `PREFLIGHT_ONLY` | `false` | Validate inputs and stop without provisioning (see [Pre-flight Only](#pre-flight-only)) |
| `ALL_CONCEPTS_JOB` | `generate-global-all-concepts` | Global AllConcepts runner job |
| `ALL_CONCEPTS_OUTPUT` | `…/global_allconcepts/` | Output location for global AllConcepts (must be `s3://`) |
| `VCF_INDEXES_JOB` | `create-vcf-indexes` | VCF index runner job |
| `VCF_INDEXES_OUTPUT` | `…/vcf_indexes/` | Output location for VCF indexes (must be `s3://`) |
| `ALL_CONCEPTS_GENERATOR_JOB` | `all-concepts-data-generator` | Per-study allConcepts runner job |
| `DECODED_DATA_DIR` | `decoded_data` | Decoded data folder, relative to `{DATA_ROOT}/{study_id}/` |
| `CONCEPT_MAPPING_FILE` | `mappings/mapping2.csv` | Concept mapping file, relative to `{DATA_ROOT}/{study_id}/` |
| `SKIP_ANALYSIS` | `false` | Use the mapping's data types as-is instead of re-analysing |
| `MERGE_ALLCONCEPTS_JOB` | `merge-allconcepts` | Merge runner job |
| `DB_START_JOB` | `participant-db-start` | Job that starts the participant database |
| `DB_STOP_JOB` | `participant-db-stop` | Job that dumps and stops it |
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

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) -- participant database lifecycle and dumps
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) -- adding a new job to the framework
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags
