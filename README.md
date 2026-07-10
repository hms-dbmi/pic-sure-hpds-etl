# pic-sure-hpds-etl

Microservice jobs that ingest complex data into PIC-SURE HPDS–compliant data structures.
Every job builds into **one runnable JAR**, is selected at runtime, has clearly defined
input/output expectations, and exits with a meaningful code so an orchestrator
(**Jenkins**) can chain jobs and gate on success.

## Stack

- **Java 21**, **Spring Boot 3.3**, packaged as a single fat JAR (`target/hpds-etl.jar`)
- **Spring `NamedParameterJdbcTemplate`** for bulk, idempotent upserts into AWS RDS Postgres
- **AWS SDK v2 S3** + local filesystem behind one `IoResolver` (`s3://` or local paths)
- **Jackson** for JSON and CSV/TSV
- **JUnit 5 + Testcontainers** (Postgres + LocalStack) for integration tests

## Run

```bash
./mvnw clean package

# Run one job
java -jar target/hpds-etl.jar --job=participants-migration \
  --input=s3://hpds-migration/participants.csv

# List jobs and their parameters
java -jar target/hpds-etl.jar --help

# Run an in-process pipeline (local/CI; prod chaining is Jenkins stages)
java -jar target/hpds-etl.jar --pipeline=migrate-all --input=./participants.csv
```

Configuration (DB, AWS, reports dir) is environment-driven — see
[`application.yml`](src/main/resources/application.yml). Nothing is hard-coded; RDS
creds and AWS credentials come from env / the Jenkins agent's IAM role.

## How it works

```
--job=<name>  ──▶  JobLauncher  ──▶  JobExecutor  ──▶  Job.run()
                       │                  │               (AbstractJob lifecycle:
                       │                  │                validate ▸ execute ▸
                       │                  │                validate ▸ report)
                       │                  ├─▶ ValidationReport + metrics
                       │                  └─▶ ReportWriter  ──▶ reports/<job>-<runId>.json
                       └─▶ process exit code  ──▶  Jenkins gates the next stage
```

- **Exit codes** are the contract with Jenkins: `0` success, `2` validation, `3` data,
  `4` infrastructure, `5` config, `1` unknown.
- **Reports** — every run writes an archivable JSON report of what was validated,
  processed, and why it failed.
- **Pipelining** — the DAG lives in the [`Jenkinsfile`](Jenkinsfile) (one stage per job);
  an in-process `PipelineRunner` mirrors it for local/CI runs.

## Target schema (AWS RDS Postgres)

Reference DDL: [`src/main/resources/db/schema.sql`](src/main/resources/db/schema.sql)
(used to initialize the Postgres Testcontainer; **not** auto-run against RDS).

| Table | Maps HPDS uuid to | Unique on |
|-------|-------------------|-----------|
| `participants` | origin ids (`source_id`, `source`) | `(source_id, source)` |
| `consents` | `study_id` / `consent_group` | `(hpds_uuid, study_id)` |
| `samples` | `source_sample_id` / `sample_source` | full triple |

## Project layout

```
etl/
├─ EtlApplication            entry point (runs one job/pipeline, then System.exit(code))
├─ runner/JobLauncher        parses --job/--pipeline, produces the exit code
├─ core/
│  ├─ job/                   Job, AbstractJob, JobContext, JobResult, ExitCode,
│  │                         JobExecutor, JobRegistry, expectations
│  ├─ validation/            ValidationReport / ValidationIssue / Severity
│  ├─ exception/             typed failures mapped to exit codes
│  ├─ report/                ReportWriter (JSON artifacts)
│  ├─ io/                    IoResolver (s3/local), DelimitedReader, JsonReader
│  └─ pipeline/              PipelineRunner (in-process chaining)
├─ config/                   EtlProperties, AwsConfig
├─ db/                       Participant/Consent/Sample repositories (JdbcTemplate)
├─ model/                    Participant, Consent, Sample
└─ jobs/
   ├─ template/TemplateJob           COPY-ME plug-and-play example
   └─ migration/ParticipantsMigrationJob   temporary migration example
```

## Adding a job

See **[docs/ADDING_A_JOB.md](docs/ADDING_A_JOB.md)** — copy `TemplateJob`, fill in five
hooks, add tests (success + every failure), wire a Jenkins stage. No registry to edit.

## Tests

```bash
./mvnw test                 # unit tests (fast, no Docker)
./mvnw verify               # + integration tests (needs Docker for Testcontainers)
```

Integration tests (`*IT`) require a running Docker daemon.
