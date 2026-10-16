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
- [Stage 9: AllConcepts Manifest](#stage-9-allconcepts-manifest)
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
          │  Start participant DB │  participant-db-start: restore participant_db.tar from S3
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
          │  Merge AllConcepts    │  (consent folders of ready studies whose sources changed)
          └───────────┬───────────┘
                      ▼
          ┌───────────────────────┐
          │  AllConcepts manifest │  verifies every ready consent group's MERGED file, then
          │                       │  writes {DATA_ROOT}/allConcepts_manifest.csv (versioned)
          └───────────────────────┘

post { always }:  participant-db-backup  (pg_dump to S3, DB stays up; participant_db.tar overwritten only on SUCCESS/UNSTABLE)
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

The per-study jobs (`sstr-populate-rds-participants` and `all-concepts-data-generator`) build
nothing on the Jenkins host: after the participant DB is up, a `Package runner image` stage builds
the runner image once and passes it to every per-study build as `PREBUILT_IMAGE_TAR`, which makes
them skip their own JAR build and image packaging. The orchestrator deletes the image (S3 tarball,
local tarball, and the host's podman image) in `post { always }`. Run standalone, those jobs still
build their own.

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

Makes sure the participant database, a long-lived PostgreSQL 16 container on the Jenkins host, is
up, and saves its connection details to a `600`-mode file on the Jenkins server, which the
DB-backed runners' Terraform applies pass to them. A clean running database is kept;
`OVERWRITE_DB` replaces it from `DB_RESTORE_FROM` (blank = the latest clean backup), and after a
failed run `IF_LAST_RUN_FAILED` decides between restoring the clean backup (default) and
continuing on that run's data. A database that isn't up is created from the latest clean backup.
Skipped under `PREFLIGHT_ONLY`. Details: [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

---

## Stage 4: Load SSTR Participants

**Job:** `sstr-populate-rds-participants`
**Class:** [`SstrPopulateRdsParticipantsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/participants/SstrPopulateRdsParticipantsJob.java)
**Runs:** once per unprocessed study — **sequentially** by default; `PARALLEL_STUDY_LOADS=true`
loads them concurrently, at most `STUDY_CONCURRENCY` (default 5) at a time (correct, see
Concurrency in [`JENKINS.md`](JENKINS.md)). With `CONTINUE_ON_STUDY_FAILURE=false`, no new study
starts after the first failure (in parallel mode, builds already running finish).

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
**Runs:** once per unprocessed study — sequentially by default, concurrently (at most
`STUDY_CONCURRENCY` at a time) with `PARALLEL_STUDY_LOADS=true`. Receives `PREFLIGHT_ONLY` and
`SKIP_ANALYSIS`.

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
  marker, and it changes the folder's source fingerprint, so the merge rebuilds it
  (`SOURCES_CHANGED`). Each removal is a
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

- `DATA_ROOT`: the `s3://` root of the per-study folders. The bucket **must have versioning
  Enabled**; the job (and its pre-flight) refuses otherwise.
- `MANAGED_INPUTS`: every study marked ready is checked. (`STUDY_IDS` on the runner job checks
  just those studies instead, for manual runs; the orchestrators never pass it.)

Only `{study_id}/allConcepts/c{code}/` folders are scanned, never `{study_id}/legacy/allConcepts/`
or the rest of the study folder. A folder's **sources** are the files directly inside it whose
name contains `_allConcepts_`, from every producer: Stage 7's generator, the migration's
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
fails with the first failure in folder order.

### Output

An up-to-date `{study_id}_c{code}_allConcepts_MERGED.csv` in each consent folder of every ready
study, and a report with `studiesChecked`, `consentFoldersDiscovered`, `foldersMerged`,
`foldersSkipped`, `foldersRemoved`, `studiesWithoutFolders`, and the action per folder.
Warnings (build UNSTABLE): a ready study with no consent folders, a folder with no source files,
and a MERGED file deleted because its sources are gone.

Runs only if Stage 7 succeeded (or was skipped because no study is unprocessed).

---

## Stage 9: AllConcepts Manifest

**Job:** `generate-allconcepts-manifest`
**Class:** [`GenerateAllConceptsManifestJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateAllConceptsManifestJob.java)
**Runs:** once, last (skipped under `PREFLIGHT_ONLY`). Touches no database.

Writes **`{DATA_ROOT}/allConcepts_manifest.csv`**, the file handed to production ingestion:

1. Always first, a row with partition `global` for the current version of
   `global_AllConcepts.csv` from Stage 5 (`ALL_CONCEPTS_OUTPUT`).
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
failure (`MERGED_MISSING`, `MERGED_STALE`, `MERGED_NOT_FINGERPRINTED`, `MERGED_WITHOUT_SOURCES`,
`MD5_MISSING`, `NO_CONSENT_FOLDERS`, `NO_READY_STUDIES`), or a global file that is missing,
empty or unversioned (`GLOBAL_MISSING`, `GLOBAL_EMPTY`, `GLOBAL_NOT_VERSIONED`), means **no
manifest is written**: the
previous version stays current, and the job exits `2` listing every problem at once. A consent
folder holding no allConcepts files at all is left out with a warning.

**Versioning.** The manifest is overwritten in place, on a bucket that must have versioning
Enabled, as must the global file's bucket (both checked by the job and its pre-flight); every earlier manifest stays retrievable as an
older version. The job fails if S3 returns no version id for the write. Metadata `row-count` and
`run-id` are set on each version, and a copy is archived with the build's reports.

---

## Post: Back Up Participant DB

**Job:** `participant-db-backup` ([`etl-runners/participant-db/Jenkinsfile.backup`](../etl-runners/participant-db/Jenkinsfile.backup))

Runs in `post { always }` whenever this build's start succeeded. It `pg_dump`s the database to
S3 and verifies the upload; the database stays up. The dump becomes the latest clean backup
(`participant_db.tar`) only if the build result so far is SUCCESS or UNSTABLE; a failed or
aborted run is dumped to `participant_db_not_promoted.tar` instead and the live database is
marked as holding a failed run, which the next start handles per `IF_LAST_RUN_FAILED`. If the
backup fails, the build fails: re-run `participant-db-backup`. See
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).

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
| `MANAGED_INPUTS` | (blank) | **Required.** Managed inputs CSV URI |
| `DATA_ROOT` | `…/BAM_testing` | Root of the per-study folders, one per study id (must be `s3://`, versioned bucket). SSTRs are discovered in `{DATA_ROOT}/{study_id}/rawData/`; the generator writes `{study_id}/allConcepts/c{code}/` files there and the merge reads them; shared with the migration's `DATA_ROOT` |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `SSTR_JOB` | `sstr-populate-rds-participants` | SSTR runner job |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep loading remaining studies when one fails |
| `PARALLEL_STUDY_LOADS` | `false` | Run the per-study stages concurrently instead of one study at a time |
| `STUDY_CONCURRENCY` | `5` | With `PARALLEL_STUDY_LOADS`: most per-study builds (and EC2 runners) at once; the rest wait for a free slot |
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
| `MERGE_ALLCONCEPTS_PARALLELISM` | `8` | Consent folders merged at once, 1-16 |
| `MERGE_ALLCONCEPTS_TIMEOUT_SECONDS` | `14400` | How long the merge may run before it is failed as stuck (exit 124) |
| `ALLCONCEPTS_MANIFEST_JOB` | `generate-allconcepts-manifest` | Manifest runner job (last stage) |
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

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) -- participant database lifecycle and dumps
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) -- adding a new job to the framework
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags
