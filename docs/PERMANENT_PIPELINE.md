# Permanent Ingestion Pipeline

The ongoing production pipeline that loads biomedical study data into HPDS. It populates
the RDS participant/consent/sample tables, generates the allConcepts CSVs that HPDS ingests,
and creates VCF indexes for genomic studies.

Orchestrated by [`/Jenkinsfile`](../Jenkinsfile). All jobs have `JobType.PERMANENT`.

## Table of Contents

- [Pipeline DAG](#pipeline-dag)
- [Trigger Modes](#trigger-modes)
- [Stage 1: Build and Test](#stage-1-build-and-test)
- [Stage 2: Resolve Studies](#stage-2-resolve-studies)
- [Stage 3: Load SSTR Participants](#stage-3-load-sstr-participants)
- [Stage 4: Generate Global AllConcepts](#stage-4-generate-global-allconcepts)
- [Stage 5: Create VCF Indexes](#stage-5-create-vcf-indexes)
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
          │  Load SSTR participants│  (per unprocessed study, sequential)
          │  sstr-populate-rds-   │
          │  participants         │
          └───────────┬───────────┘
                      │
                      ▼
          ┌───────────────────────┐
          │  Generate global      │  (all ready studies, one run)
          │  AllConcepts          │
          │  generate-global-     │
          │  all-concepts         │
          └───────────┬───────────┘
                      │
                      ▼
          ┌───────────────────────┐
          │  Create VCF indexes   │  (genomic studies only, one run)
          │  create-vcf-indexes   │
          └───────────────────────┘
```

Each stage runs only if the previous stage succeeded. The `all-concepts-data-generator` job
is not shown in this DAG because it runs in a separate per-study pipeline outside this
orchestrator; its output is consumed by the global AllConcepts stage.

---

## Trigger Modes

| Mode | `STUDY_ID` | Behavior |
|------|------------|----------|
| **Sweep** | blank | Loads every study marked "Data is ready to process" = Yes in managed inputs. Skips studies already marked "Data Processed". |
| **Single study** | `phs######` | Loads exactly that study (reload or manual entry). `INPUT` overrides the default SSTR URI. |

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

Reads the managed inputs CSV (from S3 or local) to build the list of studies to process.

**In sweep mode:**
1. Parses the managed inputs CSV for columns: `Study Abbreviated Name`, `Study Identifier`,
   `Data is ready to process`, `Data Processed`.
2. Selects studies where `Data is ready to process = Yes`.
3. Splits into unprocessed (will run SSTR load + VCF indexes) and already-processed (skipped
   for DB population, still included in global AllConcepts regeneration).
4. Derives each study's SSTR input URI as `{INPUT_BASE}/{study_id}_sstr.tsv`.
5. Validates all study IDs match `phs######` and checks for duplicates.

**In single-study mode:**
1. Uses `STUDY_ID` directly with `INPUT` (or derives from `INPUT_BASE`).

---

## Stage 3: Load SSTR Participants

**Job:** `sstr-populate-rds-participants`
**Class:** [`SstrPopulateRdsParticipantsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/participants/SstrPopulateRdsParticipantsJob.java)
**Runs:** once per unprocessed study, sequentially

### Input

A dbGaP SSTR subject/sample mapping TSV file with columns:
- `dbgap_subject_id`
- `dbgap_sample_id`
- `CONSENT` (consent group code)
- `consent_abbreviation`

### Flow

```
Read SSTR TSV (from S3 or local)
        │
        ▼
Purge existing consents for this study_id
        │
        ▼
Upsert participants                      one per distinct dbgap_subject_id
  (ON CONFLICT DO NOTHING)               source = "DBGap"
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

Populated RDS tables: `participants`, `consents`, `samples`. A JSON report with row/insert
counts.

### Alternative: Single-Consent Studies

Studies without an SSTR file use `single-consent-data-populate-rds-participants` instead.
This job reads a simple CSV of subject IDs and applies a uniform consent (either GRU for
"single" consent type or blank for "public").

---

## Stage 4: Generate Global AllConcepts

**Job:** `generate-global-all-concepts`
**Class:** [`GenerateGlobalAllConceptsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/allconcepts/GenerateGlobalAllConceptsJob.java)
**Runs:** once, covering all ready studies

### Input

- Managed inputs CSV (to discover all ready studies)
- RDS tables: `participants`, `consents`, `samples` (populated by Stage 3)

### Flow

```
Read managed inputs ─▶ filter to ready studies
        │
        ▼
For each ready study:
  ├─ Query consents from RDS      ─▶ build _consents concept rows
  │                                   (consent group membership per patient)
  │
  ├─ Query participants from RDS   ─▶ build _source_subject_id concept rows
  │                                   (participant identifier per patient)
  │
  ├─ Query samples from RDS       ─▶ build _source_sample_id concept rows
  │                                   (sample identifier per patient)
  │
  └─ Combine study + consent info  ─▶ build _studies_consents concept rows
                                      (study-level and individual consent paths)
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

### Related: Per-Study AllConcepts

The `all-concepts-data-generator` job produces per-study, per-consent allConcepts files from
decoded phenotypic data. It reads mapping CSVs and decoded data files, resolves patient IDs
to HPDS UUIDs via RDS, and outputs files in the structure
`{output}/{study_id}/c{consent_code}/{study_id}_allConcepts_c{consent_code}.csv`. This job
runs in a separate per-study pipeline outside this orchestrator.

---

## Stage 5: Create VCF Indexes

**Job:** `create-vcf-indexes`
**Class:** [`CreateVCFIndexesJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/genomic/CreateVCFIndexesJob.java)
**Runs:** once, only if there are unprocessed studies

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
    │                               with sample/patient ID lists
    │
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
                    SSTR TSV / Subject CSV
                           │
                           ▼
                   ┌───────────────┐
                   │  RDS Tables   │
                   │  participants │
                   │  consents     │
                   │  samples      │
                   └───────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
     global_AllConcepts  per-study    VCF indexes
         .csv          allConcepts   + SampleIds
                         .csv          .csv/.tsv
              │            │            │
              └────────────┼────────────┘
                           ▼
                     HPDS ingestion
```

---

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `STUDY_ID` | (blank) | Blank for sweep mode; `phs######` for single study |
| `INPUT` | (blank) | Override SSTR input URI (single-study mode only) |
| `MANAGED_INPUTS` | (blank) | Managed inputs CSV URI; required in sweep mode |
| `INPUT_BASE` | `s3://avillach-73-bdcatalyst-etl/sstr/` | Base S3 path for SSTR files |
| `BATCH_SIZE` | `1000` | Rows per batch insert |
| `RUN_INTEGRATION_TESTS` | `true` | Run Testcontainers IT suites |
| `CONTINUE_ON_STUDY_FAILURE` | `true` | Keep loading remaining studies when one fails |
| `PREFLIGHT_ONLY` | `false` | Validate inputs and stop without loading |
| `ALL_CONCEPTS_OUTPUT` | `s3://avillach-etl/output/` | Output location for global AllConcepts |
| `VCF_INDEXES_OUTPUT` | `s3://avillach-etl/output/vcf-indexes/` | Output location for VCF indexes |

---

## Exit Codes

| Code | Name | Meaning |
|-----:|------|---------|
| 0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | Study loaded; warnings mark the build UNSTABLE |
| 1 | `UNKNOWN` | Unhandled failure |
| 2 | `VALIDATION_FAILED` | Input or output validation failed |
| 3 | `DATA_ERROR` | Data-level failure; study transaction rolled back |
| 4 | `INFRASTRUCTURE_ERROR` | Retryable infrastructure issue |
| 5 | `CONFIG_ERROR` | Missing parameter or disabled job |

---

## References

- [`docs/JENKINS.md`](JENKINS.md) -- runner infrastructure and deployment details
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) -- adding a new job to the framework
- [`application.yml`](../src/main/resources/application.yml) -- job enablement flags
