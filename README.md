# pic-sure-hpds-etl

Microservice jobs that ingest complex data into PIC-SURE HPDS–compliant data structures.
Every job builds into **one runnable JAR**, is selected at runtime, has clearly defined
input/output expectations, and exits with a meaningful code so an orchestrator
(**Jenkins**) can chain jobs and gate on success.

## Stack

- **Java 25**, **Spring Boot 4.1**, packaged as a single fat JAR (`target/hpds-etl.jar`)
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
[`application.yml`](src/main/resources/application.yml). Nothing is hard-coded. In production
each job runs on an ephemeral EC2 runner that fetches the RDS credentials from Secrets Manager
with its own instance role, so they never pass through Jenkins.

## Pipelines

Two Jenkins pipelines, separate because their lifecycles are opposites — see
**[docs/JENKINS.md](docs/JENKINS.md)**.

| Pipeline | Scope | Lifetime |
|---|---|---|
| [`Jenkinsfile`](Jenkinsfile) | **only** `JobType.MIGRATION` jobs | deleted once the migration has run everywhere |
| [`Jenkinsfile.permanent`](Jenkinsfile.permanent) | **only** `JobType.PERMANENT` jobs | ongoing |

Each orchestrator stage triggers that job's own pipeline under
[`etl-runners/`](etl-runners/), which provisions a **self-terminating EC2 runner** with
Terraform, runs the JAR in a container, publishes the exit code and JSON report to S3, and
tears itself down. Same pattern as
[`bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — shared
`terraform-modules/etl-runner` shape, `jenkins-s3-role`, `s3://<stack>/etl-runner/…` — with
Java in place of Python, and completion detected from a real exit code rather than by grepping
the log.

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
- **Pipelining** — the DAG lives in the Jenkinsfiles (one stage per job, each triggering that
  job's own runner pipeline); an in-process `PipelineRunner` mirrors it for local/CI runs.

## Target schema (AWS RDS Postgres)

Reference DDL: [`src/main/resources/repository/schema.sql`](src/main/resources/repository/schema.sql)
(used to initialize the Postgres Testcontainer; **not** auto-run against RDS).

| Table | Maps HPDS uuid to | Unique on |
|-------|-------------------|-----------|
| `participants` | origin ids (`source_id`, `source`) | `(source_id, source)` |
| `consents` | `study_id` / `consent_code` / `consent_abbreviation` | `(hpds_uuid, study_id)` |
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
   ├─ template/TemplateJob                            COPY-ME plug-and-play example
   ├─ participants/
   │  ├─ SstrPopulateRdsParticipantsJob                permanent: dbGaP SSTR TSV → RDS
   │  ├─ SingleConsentDataPopulateRdsParticipantsJob   permanent: subject-id CSV → RDS,
   │  │                                                one uniform consent per run
   │  └─ Telemetry                                     SSTR row (dbgap ids, consent)
   └─ migration/ParticipantsMigrationJob               temporary: orchestrates the above
                                                        two + direct population, per study
```

Everything Jenkins and AWS lives outside `src/`:

```
Jenkinsfile                       migration orchestrator (TEMPORARY)
Jenkinsfile.permanent             permanent ETL orchestrator
terraform-modules/etl-runner/     self-terminating EC2 runner module
etl-runners/                      one dir per job: Jenkinsfile, Makefile, terraform/,
                                  preflight.sh, validate.sh  (+ shared Dockerfile,
                                  run-job.sh, common.mk, common/)
docs/JENKINS.md                   architecture, validation, AWS setup, runbook
```

## Adding a job

See **[docs/ADDING_A_JOB.md](docs/ADDING_A_JOB.md)** — copy `TemplateJob`, fill in five
hooks, add tests (success + every failure), add a runner and a Jenkins stage. No registry to
edit, but jobs are **opt-in**: a job runs only where its `etl.jobs.<name>.enabled` flag is
`true`, so [`application.yml`](src/main/resources/application.yml) is the single list of what
an environment may run.

## Tests

```bash
./mvnw test                 # unit tests (fast, no Docker)
./mvnw verify               # + integration tests (needs Docker for Testcontainers)
```

Integration tests (`*IT`) require a running Docker daemon.
