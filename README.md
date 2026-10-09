# pic-sure-hpds-etl

Microservice jobs that ingest complex data into PIC-SURE HPDS–compliant data structures.
Every job builds into **one runnable JAR**, is selected at runtime, has clearly defined
input/output expectations, and exits with a meaningful code so an orchestrator
(**Jenkins**) can chain jobs and gate on success.

## Stack

- **Java 25**, **Spring Boot 4.1**, packaged as a single fat JAR (`target/hpds-etl.jar`)
- **Spring `NamedParameterJdbcTemplate`** for bulk, idempotent upserts into the **participant
  database**: Postgres on an ephemeral EC2 server that each pipeline run starts and stops, persisted
  between runs as `pg_dump`s in S3 (see [docs/PARTICIPANT_DB.md](docs/PARTICIPANT_DB.md))
- **AWS SDK v2 S3** + local filesystem behind one `IoResolver` (`s3://` or local paths)
- **Jackson** for JSON and CSV/TSV
- **JUnit 5 + Testcontainers** (Postgres + LocalStack) for integration tests

## Run

```bash
./mvnw clean package

# Run one job
java -jar target/hpds-etl.jar --job=participants-migration \
  --input=s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/__migration__/participants.csv

# List jobs and their parameters
java -jar target/hpds-etl.jar --help

# Run an in-process pipeline (local/CI; prod chaining is Jenkins stages)
java -jar target/hpds-etl.jar --pipeline=migrate-all --input=./participants.csv
```

Configuration (DB, AWS, reports dir) is environment-driven — see
[`application.yml`](src/main/resources/application.yml). Nothing is hard-coded. In production
each job runs on an ephemeral EC2 runner that fetches the participant database credentials
(`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`) from a temporary Secrets Manager secret with its own
instance role (`bdc-etl-jenkins-role`), so they never pass through Jenkins. The secret exists only
while the database is up.

## Pipelines

Two Jenkins pipelines, kept separate because their lifecycles are opposites. See
**[docs/JENKINS.md](docs/JENKINS.md)** for the full architecture. All Jenkins jobs live in the
`hpds-etl` folder.

| Pipeline                                         | Scope                                          | Lifetime                                      |
|--------------------------------------------------|------------------------------------------------|-----------------------------------------------|
| [`Jenkinsfile`](Jenkinsfile)                     | permanent ingestion (`JobType.PERMANENT` jobs) | ongoing                                       |
| [`Jenkinsfile.migration`](Jenkinsfile.migration) | legacy migration (plus the permanent jobs that rebuild its derived artifacts) | deleted once the migration has run everywhere |

Both start the participant database (`participant-db-start`) before their first DB-backed stage
and dump and stop it (`participant-db-stop`) at the end; the dump becomes the next run's restore
point only when the run succeeded.

Each orchestrator stage triggers that job's own pipeline under [`etl-runners/`](etl-runners/),
which provisions a self-terminating EC2 runner with Terraform, runs the JAR in a container,
publishes the exit code and JSON report to S3, and tears itself down.

The pattern follows [`bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — the same
`terraform-modules/etl-runner` shape and `s3://<stack>/etl-runner/…` layout — with Java in place
of Python. Every instance runs as `bdc-etl-jenkins-role` in the same account as the data
(`515157839325`), so no cross-account role is assumed.

## How It Works

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

## Target Schema (participant database, `etl` schema)

Reference DDL: [`src/main/resources/repository/schema.sql`](src/main/resources/repository/schema.sql).
It initializes the Postgres Testcontainer, and `participant-db-start` runs it when there is no
dump to restore; it is **not** run at application startup. Once a dump exists, the dump is the
schema of record.

Every HPDS identity is an integer `hpds_id` drawn from one shared sequence, `hpds_id_seq`
(carried across runs by the dump).

| Table          | Maps `hpds_id` to                                    | Unique on                                         |
|----------------|------------------------------------------------------|---------------------------------------------------|
| `participants` | origin ids (`source_id`, `source`)                   | `(source_id, source)`                             |
| `consents`     | `study_id` / `consent_code` / `consent_abbreviation` | `(hpds_id, study_id)`                             |
| `samples`      | `source_sample_id` / `sample_source`                 | `(hpds_id, source_sample_id, sample_source)`      |

## Project Layout

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
│  ├─ util/                  BatchOps, Strings
│  └─ pipeline/              PipelineRunner (in-process chaining)
├─ config/                   EtlProperties, AwsConfig, AssumedRoleS3Clients (NHLBI exchange)
├─ repository/               Participant/Consent/Sample repositories (JdbcTemplate)
├─ service/                  ManagedInputsService (the managed inputs CSV)
├─ model/                    Participant, Consent, Sample, allConcepts rows and builders
└─ jobs/
   ├─ template/TemplateJob                            COPY-ME plug-and-play example
   ├─ participants/
   │  ├─ SstrPopulateRdsParticipantsJob                permanent: dbGaP SSTR TSV → participant DB
   │  ├─ SingleConsentDataPopulateRdsParticipantsJob   permanent: subject-id CSV → participant DB,
   │  │                                                one uniform consent per run
   │  └─ Telemetry                                     SSTR row (dbgap ids, consent)
   ├─ allconcepts/
   │  ├─ AllConceptsDataGeneratorJob                   permanent: per-study, per-consent allConcepts
   │  ├─ GenerateGlobalAllConceptsJob                  permanent: global_AllConcepts.csv
   │  └─ MergeAllConceptsJob                           permanent: merge per-consent allConcepts files
   ├─ genomic/CreateVCFIndexesJob                      permanent: vcfIndex.tsv + SampleIds.csv
   ├─ harmonized/GenerateIdentityConsentMappingJob     permanent: DMC drop → identity/consent CSV
   └─ migration/
      ├─ ParticipantsMigrationJob                      temporary: legacy ids → participant DB
      └─ SplitAllConceptsJob                           temporary: legacy allConcepts → per consent
```

Everything Jenkins and AWS lives outside `src/`:

```
Jenkinsfile                       permanent ETL orchestrator
Jenkinsfile.migration             migration orchestrator (TEMPORARY)
terraform-modules/etl-runner/     self-terminating EC2 runner module
etl-runners/                      one dir per job: Jenkinsfile, Makefile, terraform/,
                                  preflight.sh, validate.sh  (+ shared Dockerfile,
                                  run-job.sh, common.mk, common/)
etl-runners/participant-db/       the per-run Postgres server: Jenkinsfile.start/.stop,
                                  terraform/, backup and wait scripts
etl-runners/environments/         per-environment tfvars (development.tfvars)
docs/JENKINS.md                   architecture, validation, AWS setup, runbook
docs/PARTICIPANT_DB.md            participant database lifecycle, dumps, seeding
docs/PERMANENT_PIPELINE.md        permanent pipeline stage by stage
docs/MIGRATION_PIPELINE.md        migration pipeline stage by stage
docs/ADDING_A_JOB.md              adding a job and its runner
```

## Adding a Job

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

Integration tests (`*IT`) require a running Docker daemon (Testcontainers Postgres + LocalStack).
Jenkins runs them in AWS CodeBuild instead, so its agent needs no Docker; see
[docs/JENKINS.md](docs/JENKINS.md#3-integration-tests-in-codebuild).
