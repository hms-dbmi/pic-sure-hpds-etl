# Migration Pipeline (Temporary)

A one-off pipeline that migrates legacy HPDS data into the new RDS-backed system. It
populates the RDS participant/consent/sample tables from legacy mapping files, splits
existing allConcepts files by consent group (replacing old integer HPDS IDs with new
UUIDs), then generates the global AllConcepts and VCF indexes from the migrated data.

Orchestrated by [`/Jenkinsfile.migration`](../Jenkinsfile.migration). The first two jobs
have `JobType.MIGRATION`; the last two are `JobType.PERMANENT` jobs reused here to build
the derived artifacts from newly migrated data.

**This pipeline is temporary.** Once the migration has run in every environment, delete:
- `Jenkinsfile.migration`
- `etl-runners/participants-migration/`
- `src/.../jobs/migration/ParticipantsMigrationJob.java` (and its tests)
- `src/.../jobs/migration/SplitAllConceptsJob.java` (and its tests)
- The `etl.pipelines.migrate-all` entry in `application.yml`
- The migration job flags in `application.yml`

## Table of Contents

- [Pipeline DAG](#pipeline-dag)
- [Stage 1: Build and Test](#stage-1-build-and-test)
- [Stage 2: Migrate Participants](#stage-2-migrate-participants)
- [Stage 3: Split AllConcepts](#stage-3-split-allconcepts)
- [Stage 4: Generate Global AllConcepts](#stage-4-generate-global-allconcepts)
- [Stage 5: Create VCF Indexes](#stage-5-create-vcf-indexes)
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
│  Migrate participants    │  (all ready studies, one run)
│  participants-migration  │
└────────────┬────────────┘
             │
             │  produces hpds_id_mapping.csv per study
             ▼
┌─────────────────────────┐
│  Split AllConcepts       │  (per study, sequential)
│  split-allconcepts       │
└────────────┬────────────┘
             │
             │  produces {study_id}/c{code}/{ABV}_allConcepts_c{code}.csv per consent
             ▼
┌─────────────────────────┐
│  Generate global         │  (all ready studies, one run)
│  AllConcepts             │
│  generate-global-        │
│  all-concepts            │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Create VCF indexes      │  (genomic studies only, one run)
│  create-vcf-indexes      │
└─────────────────────────┘
```

Stage ordering is strict: each stage runs only if the previous stage succeeded.
`split-allconcepts` depends on the mapping files from `participants-migration`.
The global AllConcepts and VCF index stages read from the RDS tables populated by
`participants-migration`.

The same four jobs can also be run locally via `--pipeline=migrate-all`, which uses
the in-process `PipelineRunner` defined in `application.yml`.

---

## Stage 1: Build and Test

```
./mvnw clean package -DskipTests
./mvnw verify                       (or ./mvnw test if RUN_INTEGRATION_TESTS is off)
```

Same gate as the permanent pipeline: the full test suite runs once here, and downstream
jobs skip tests.

---

## Stage 2: Migrate Participants

**Job:** `participants-migration`
**Class:** [`ParticipantsMigrationJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/ParticipantsMigrationJob.java)
**Runs:** once, processing all ready studies internally

### Input

| File | Source | Description |
|------|--------|-------------|
| Managed inputs CSV | `--managed-inputs` | Study master list with readiness flags |
| `GLOBAL_allConcepts_merged.csv` | `--data-folder` | Legacy file with consent codes and abbreviations per old HPDS integer ID |
| `{ABV}_PatientMapping.v2.csv` | `--data-folder` | Per-study mapping of old subject IDs to legacy HPDS integer IDs |
| `{studyid}_sstr.tsv` | `--data-folder` | Per-study SSTR file (optional; determines processing path) |

### Flow

```
Read managed inputs ─▶ filter to ready studies
Read GLOBAL_allConcepts_merged.csv ─▶ build consent lookup (old HPDS ID → consent info)
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
        │                           Join patient mapping against SSTR file
        │                                       │
        │                                       ▼
        │                           Build mapping: old HPDS ID → new UUID → dbGaP ID
        │
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
                                    Optionally upsert samples
                                    (all in one transaction per study)
                                                │
                                                ▼
                                    Build mapping: old HPDS ID → new UUID → common ID
        │
        ▼
Write {studyid}_hpds_id_mapping.csv to reports directory
```

Studies are processed independently. A failure in one study does not stop the rest (unless
it is an infrastructure failure). The job exits with `VALIDATION_FAILED` if any study
failed while others succeeded.

### Output

- Populated RDS tables: `participants`, `consents`, `samples`
- One `{studyid}_hpds_id_mapping.csv` per study with columns:
  - `old_hpds_id` -- the legacy integer HPDS ID
  - `new_uuid` -- the new UUID assigned in the RDS system
  - `common_dbgap_id` -- the dbGaP subject ID (or source ID for non-SSTR studies)

### Special Cases

- **`open_access-1000Genomes`**: handled on the direct path with sample rows written
  (subject IDs are also sample IDs for this dataset)

---

## Stage 3: Split AllConcepts

**Job:** `split-allconcepts`
**Class:** [`SplitAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/SplitAllConceptsJob.java)
**Runs:** once per ready study, sequentially (driven by the Jenkins stage loop)

### Input

| File | Source | Description |
|------|--------|-------------|
| Legacy allConcepts CSV | S3: `{abv}/completed/{study_id}/{study_id}_allConcepts_new_search_with_data_analyzer.csv` | The study's unified allConcepts file |
| `{studyid}_hpds_id_mapping.csv` | Stage 2 output | Maps old integer IDs to new UUIDs |
| RDS `consents` table | Database | Consent assignments for the study |

### Flow

```
Read hpds_id_mapping.csv ─▶ build lookup: old HPDS ID → new UUID
        │
        ▼
Read legacy allConcepts CSV (streaming)
        │
        ▼
For each row:
  ├─ Replace old integer HPDS ID with new UUID (via mapping)
  └─ Look up patient's consent group from RDS
        │
        ▼
Route row to the appropriate per-consent output file
        │
        ▼
Write per-consent files:
  {output}/split_allconcepts/{study_id}/c{code}/{ABV}_allConcepts_c{code}.csv
```

### Output

Per-consent allConcepts files at
`{output}/split_allconcepts/{study_id}/c{code}/{ABV}_allConcepts_c{code}.csv`, matching the
nested structure used by `all-concepts-data-generator`
(`{output}/{study_id}/c{code}/{study_id}_allConcepts_c{code}.csv`). Both jobs use the same
layout: study ID folder, consent subfolder, individual allConcepts file per consent.

- Old integer HPDS IDs replaced by new UUIDs
- One file per consent group instead of one unified file

### Error Handling

- Rows with unmapped HPDS IDs are logged as warnings and skipped
- `CONTINUE_ON_STUDY_FAILURE` (default `true`) allows the remaining studies to continue
  when one fails

---

## Stage 4: Generate Global AllConcepts

**Job:** `generate-global-all-concepts`
**Class:** [`GenerateGlobalAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateGlobalAllConceptsJob.java)
**Runs:** once, covering all ready studies

This is the same job used by the permanent pipeline, reused here to generate the global
AllConcepts from the newly migrated RDS data.

### Input

- Managed inputs CSV (to discover all ready studies)
- RDS tables: `participants`, `consents`, `samples` (populated by Stage 2)

### Flow

```
Read managed inputs ─▶ filter to ready studies
        │
        ▼
For each ready study:
  ├─ Query consents from RDS      ─▶ build _consents concept rows
  ├─ Query participants from RDS   ─▶ build _source_subject_id concept rows
  ├─ Query samples from RDS       ─▶ build _source_sample_id concept rows
  └─ Combine study + consent info  ─▶ build _studies_consents concept rows
        │
        ▼
Aggregate all studies into one CSV
        │
        ▼
Write global_AllConcepts.csv to --output (S3 or local)
```

### Output

A single `global_AllConcepts.csv` containing concept rows for consent, subject, sample, and
study metadata across all ready studies.

---

## Stage 5: Create VCF Indexes

**Job:** `create-vcf-indexes`
**Class:** [`CreateVCFIndexesJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/genomic/CreateVCFIndexesJob.java)
**Runs:** once, genomic studies only

This is the same job used by the permanent pipeline, reused here to generate VCF indexes
from the newly migrated RDS data.

### Input

- Managed inputs CSV (to find studies with "G" in their data type)
- RDS tables: `consents`, `samples` (for genomic studies)

### Flow

```
Read managed inputs ─▶ filter to genomic studies (data type contains "G")
        │
        ▼
For each genomic study:
  Query consents + samples from RDS
        │
        ▼
  For each consent group with NWD-prefixed samples:
    ├─ Generate vcfIndex.tsv        one row per chromosome (1-22, X)
    └─ Generate SampleIds.csv       list of sample IDs in this consent group
        │
        ▼
Write per-consent files to --output:
  {studyId}.c{code}_vcfIndex.tsv
  {studyId}.c{code}_SampleIds.csv
```

### Output

Per-consent-group VCF index and sample ID files for genomic studies.

---

## Data Flow Summary

```
LEGACY SYSTEM                             NEW RDS SYSTEM
─────────────                             ──────────────

GLOBAL_allConcepts_merged.csv ──┐
                                │
{ABV}_PatientMapping.v2.csv ────┤
                                ├──▶ participants-migration ──▶ RDS tables
{studyid}_sstr.tsv ────────────┤                            │  (participants,
                                │                            │   consents,
Managed inputs CSV ─────────────┘                            │   samples)
                                                             │
                                            ┌────────────────┼──────────────────┐
                                            │                │                  │
                                            ▼                ▼                  ▼
                                   {studyid}_hpds_     global_AllConcepts   VCF indexes
                                   id_mapping.csv         .csv             + SampleIds
                                            │                                  .csv/.tsv
Legacy allConcepts CSV ────────────────────┤
                                            │
                                            ▼
                                   split-allconcepts
                                            │
                                            ▼
                                   Per-consent allConcepts
                                   {study_id}/c{code}/{ABV}_allConcepts_c{code}.csv
```

---

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `MANAGED_INPUTS` | `s3://avillach-73-bdcatalyst-etl/__migration__/managed_inputs.csv` | Study list CSV |
| `DATA_FOLDER` | `s3://avillach-73-bdcatalyst-etl/__migration__/current` | Folder with SSTR, patient mapping, and global allConcepts files |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `SPLIT_OUTPUT` | `./output` | Output directory for split allConcepts files |
| `ALL_CONCEPTS_OUTPUT` | `s3://avillach-etl/output/` | Output location for global AllConcepts |
| `VCF_INDEXES_OUTPUT` | `s3://avillach-etl/output/vcf-indexes/` | Output location for VCF indexes |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep splitting remaining studies when one fails |
| `PREFLIGHT_ONLY` | `false` | Validate inputs and stop without migrating |

---

## Exit Codes

| Code | Name | Meaning |
|-----:|------|---------|
| 0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | Migration completed; warnings mark the build UNSTABLE |
| 1 | `UNKNOWN` | Unhandled failure |
| 2 | `VALIDATION_FAILED` | Some studies failed while others succeeded |
| 3 | `DATA_ERROR` | Data-level failure |
| 4 | `INFRASTRUCTURE_ERROR` | Retryable infrastructure issue |
| 5 | `CONFIG_ERROR` | Missing parameter or disabled job |

---

## Local Execution

The full migration pipeline can be run locally using the in-process pipeline runner:

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
first failure.

---

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/PERMANENT_PIPELINE.md`](PERMANENT_PIPELINE.md) -- the ongoing ingestion pipeline
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags and
  pipeline definition
