# Jenkins Pipelines and Ephemeral ETL Runners

This document describes how the hpds-etl jobs are orchestrated: two Jenkins pipelines, one
ephemeral EC2 runner per job, provisioned with Terraform and torn down after each run, and a
participant database that exists only for the duration of a pipeline run. Jobs are selected at
runtime from a single fat JAR and communicate outcome through process exit codes.

The infrastructure pattern follows
[`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — the same
`terraform-modules/etl-runner` shape, `s3://<stack>/etl-runner/{container,logs,reports}/` layout,
and per-pipeline `terraform/` directory driven by a `Makefile` — with Java in place of Python.

## Table of Contents

- [Pipelines](#pipelines)
- [Jenkins Jobs](#jenkins-jobs)
- [Architecture](#architecture)
- [Exit Codes](#exit-codes)
- [Validation](#validation)
- [Requirements](#requirements)
- [AWS Setup](#aws-setup)
- [Usage](#usage)
- [Job Enablement](#job-enablement)
- [Concurrency](#concurrency)
- [Directory Layout](#directory-layout)
- [Operations](#operations)
- [Known Limitations](#known-limitations)
- [References](#references)

---

## Pipelines

| Pipeline                | File                                                 | Jenkins job                                   | Scope                                                       | Lifetime                                      |
|-------------------------|------------------------------------------------------|-----------------------------------------------|-------------------------------------------------------------|-----------------------------------------------|
| **Permanent ETL**       | [`/Jenkinsfile`](../Jenkinsfile)                     | `hpds-etl-pipeline`                           | `JobType.PERMANENT` jobs                                    | ongoing                                       |
| **Temporary migration** | [`/Jenkinsfile.migration`](../Jenkinsfile.migration) | `new-hpds-etl-participant-migration-pipeline` | `JobType.MIGRATION` jobs, plus the permanent jobs that derive artifacts from migrated data | deleted once the migration has run everywhere |

The two are separate files because their lifecycles are opposites. A migration is a one-off that
ends in deletion; permanent ingestion runs indefinitely. Keeping them apart means the permanent
pipeline's schedule, retention, and alerting are not entangled with work whose endpoint is
`git rm`, and retiring the migration is a file deletion rather than an edit to the pipeline that
runs every week.

Stage-by-stage descriptions: [PERMANENT_PIPELINE.md](PERMANENT_PIPELINE.md),
[MIGRATION_PIPELINE.md](MIGRATION_PIPELINE.md).

## Jenkins Jobs

All jobs live in the Jenkins folder **`hpds-etl`** and point at this repository. Job names in the
orchestrators' parameters (`SSTR_JOB`, `ALL_CONCEPTS_JOB`, `DB_START_JOB`, …) are **relative**, so
they resolve to siblings inside the same folder.

| Jenkins job                                   | Script path                                                 |
|-----------------------------------------------|-------------------------------------------------------------|
| `hpds-etl-pipeline`                           | `Jenkinsfile`                                               |
| `new-hpds-etl-participant-migration-pipeline` | `Jenkinsfile.migration`                                     |
| `participant-db-start`                        | `etl-runners/participant-db/Jenkinsfile.start`              |
| `participant-db-stop`                         | `etl-runners/participant-db/Jenkinsfile.stop`               |
| `participants-migration`                      | `etl-runners/participants-migration/Jenkinsfile`            |
| `split-allconcepts`                           | `etl-runners/split-allconcepts/Jenkinsfile`                 |
| `sstr-populate-rds-participants`              | `etl-runners/sstr-populate-rds-participants/Jenkinsfile`    |
| `generate-global-all-concepts`                | `etl-runners/generate-global-all-concepts/Jenkinsfile`      |
| `create-vcf-indexes`                          | `etl-runners/create-vcf-indexes/Jenkinsfile`                |
| `merge-allconcepts`                           | `etl-runners/merge-allconcepts/Jenkinsfile`                 |
| `all-concepts-data-generator`                 | `etl-runners/all-concepts-data-generator/Jenkinsfile`       |
| `generate-identity-consent-mapping`           | `etl-runners/generate-identity-consent-mapping/Jenkinsfile` |

`all-concepts-data-generator` is triggered per unprocessed study by the permanent
orchestrator's `Generate per-study AllConcepts` stage, and can also be run by hand.
`generate-identity-consent-mapping` is standalone — no orchestrator triggers it; run it when a
new DMC harmonization drop lands (ALS-12727).

> **Folder names must not contain a space.** `common.mk` uses unquoted workspace paths, so a job
> in a folder such as `__Harmonization Work` breaks at `ensure-terraform` (identity-mapping
> shakeout run #2). `hpds-etl` is safe; keep every job, `generate-identity-consent-mapping`
> included, inside it.

If your naming differs, change the orchestrator's job-name parameter rather than the pipeline.

---

## Architecture

### Orchestration

The DAG lives in Jenkins, not in the JAR. Each orchestrator stage triggers that job's own
pipeline, which owns its runner end to end. The participant database is started after the
gate and stopped in `post { always }`.

```
/Jenkinsfile  (or /Jenkinsfile.migration)
  Build ▸ Tests                              gate: nothing is provisioned until these pass
  ▸ (Resolve studies)
  ▸ Start participant DB                     build job: participant-db-start
  └─ stage 'Load SSTR participants'          (one stage per job, in DAG order)
       └─ build job: sstr-populate-rds-participants    etl-runners/<job>/Jenkinsfile
            Init ▸ Build JAR ▸ Pre-flight ▸ Package image
            ▸ Provision (require-db.sh, terraform apply) ▸ Monitor ▸ Fetch reports ▸ Validate
            post: terraform destroy, archive reports
  post { always }: build job: participant-db-stop      PROMOTE_BACKUP = build SUCCESS/UNSTABLE
```

Build and test run once in the orchestrator as the gate for the whole run. Downstream jobs are
invoked with `SKIP_TESTS=true` so the same commit's suites are not re-run per study.

The orchestrator stops only a database it started itself (`DB_STARTED`), so a failed start —
for example because another pipeline's database is already up — never tears down someone else's.
The participant DB lifecycle, dump location, and recovery are in
[PARTICIPANT_DB.md](PARTICIPANT_DB.md).

### Runner Lifecycle

```
Jenkins agent                          ephemeral EC2 (self-terminating)
─────────────                          ────────────────────────────────
./mvnw package        ─ target/hpds-etl.jar
docker buildx build   ─ hpds-etl-runner image
docker save | gzip    ─▶ s3://<stack>/etl-runner/container/<run>.tar.gz
require-db.sh         ─ DB-backed runners only: fail fast if the participant DB is down
terraform apply       ─▶ launch instance ──▶ user_data:
                                              fetch participant DB secret (instance role)
                                                → DB_URL / DB_USERNAME / DB_PASSWORD
                                                (skipped for DB-free jobs)
                                              podman load + podman run
                                              java -jar hpds-etl.jar --job=… --run-id=…
                                              sync /reports  ─▶ s3://…/etl-runner/reports/<run>/
                                              upload log     ─▶ s3://…/etl-runner/logs/<run>.log
                                              upload status.json  (sentinel, last)
                                              shutdown now   (terminate)
monitor-runner.sh     ◀─ polls EC2 state, tails log via SSM
                      ◀─ reads status.json, exits with the job's exit code
aws s3 sync reports   ◀─ the JSON report and any CSVs
validate.sh           ─ assertions over the report
terraform destroy     (post: always)
```

`merge-allconcepts` and `generate-identity-consent-mapping` touch no database: their module call
passes `db_secret_id = ""`, so the credential fetch is skipped and they run whether or not the
participant DB is up.

Properties of this model:

- **One principal.** Runners, the participant DB, and the Jenkins agent all run as
  `bdc-etl-jenkins-role` in account 515157839325, which also owns the data. No cross-account role
  is assumed. The only remaining assume is `generate-identity-consent-mapping`'s in-process
  `--role-arn` (the NHLBI exchange role), made by the instance role.
- **No SSH.** Access is AWS SSM Session Manager only; no instance has a key pair.
- **No long-lived ETL host** and no Jenkins agent holding database credentials.
- **Self-terminating runners.** A runner terminates whether the job succeeded, failed, was
  OOM-killed, or had its spot capacity reclaimed. (The participant DB deliberately does not; see
  [PARTICIPANT_DB.md](PARTICIPANT_DB.md).)
- **Completion is a sentinel, not a log match.** `status.json` carries the job's exit code and is
  uploaded last, so its presence also proves every other artifact reached S3.

---

## Exit Codes

`ExitCode.java` is the interface between a job and Jenkins.

| Code | Name                                | Pipeline behaviour                                                                   |
|-----:|-------------------------------------|--------------------------------------------------------------------------------------|
|    0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | continue; the report distinguishes the two, and warnings mark the build UNSTABLE     |
|    1 | `UNKNOWN`                           | fail                                                                                 |
|    2 | `VALIDATION_FAILED`                 | fail. For the migration this also means *some studies failed while others succeeded* |
|    3 | `DATA_ERROR`                        | fail. For SSTR the study was rolled back, so the participant DB is unchanged         |
|    4 | `INFRASTRUCTURE_ERROR`              | **retried once**, then fail                                                          |
|    5 | `CONFIG_ERROR`                      | fail; no retry — a retry cannot fix a missing parameter                              |
|  124 | (monitor)                           | timed out waiting for the runner; no retry                                           |

A DB-backed runner started while the participant DB is down exits `5` in its `credentials` phase
(the secret does not exist). `common/require-db.sh` normally catches this on the agent first, in
seconds, before anything is provisioned.

---

## Validation

Four layers, each catching what the others cannot.

### 1. Test Suites

`./mvnw verify` runs the unit suites plus the Testcontainers `*IT` suites against real Postgres
and LocalStack. These are the only checks that assert real database state; every later layer
reasons about report metrics. Run by the orchestrators, skipped in the job pipelines.

### 2. Pre-flight Checks

`etl-runners/<job>/preflight.sh` runs on the agent before any instance is provisioned, checking
what is knowable from the input files alone (header shape, required columns, object existence,
study-id format). Each runner's script documents its own checks. `PREFLIGHT_ONLY=true` on an
orchestrator runs these for every job that has them and provisions nothing — the participant DB
included; stages without a pre-flight mode (global AllConcepts, merge) are skipped.

`all-concepts-data-generator`'s pre-flight is the strictest about its output: the mapping file
must exist, the decoded data folder must hold at least one `.csv`, and `OUTPUT` must be an
`s3://` prefix on a bucket with **versioning Enabled** — the job overwrites per-consent files in
place and removes stale ones, so versioning is what keeps every previous version recoverable.

### 3. Job Lifecycle

`AbstractJob` validates required parameters, then inputs, then executes, then asserts
post-conditions in `validateOutput`. Any ERROR-level issue fails the run and is recorded in the
report.

### 4. Report Assertions

`etl-runners/<job>/validate.sh` asserts over the JSON report. The job's exit code and its output
are treated as independent gates: a load that exits `0` but wrote fewer consent rows than it
found subjects is still a failed load.

**All jobs** (`common/validate-report.sh`): valid JSON, `status == SUCCESS`, a success exit code,
zero input- and output-validation errors, no `errorMessage`. Warnings are surfaced, not swallowed.

**`sstr-populate-rds-participants`** — two metrics are exact invariants rather than heuristics:

| Assertion                                           | Why it holds                                                                                                                                             |
|-----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `consentsWritten == distinctParticipants`           | One consent row is built per distinct `dbgap_subject_id`, and `ConsentRepository` upserts with `ON CONFLICT DO UPDATE`, so every row reports 1 affected. |
| `sum(countsByConsentGroup) == distinctParticipants` | The same one-row-per-subject set, grouped by `CONSENT`.                                                                                                  |

`participantsInserted` is deliberately not asserted equal to anything: `ParticipantRepository`
upserts with `ON CONFLICT DO NOTHING`, so it counts only new participants and is legitimately `0`
on a reload. That case is reported as a warning, since it is correct for a reload and wrong for a
study's first load.

The optional job parameters `EXPECTED_CONSENT_CODES` and `EXPECTED_MIN_PARTICIPANTS` catch a file
swapped for the wrong study and a truncated input — the two failures no invariant can detect,
because a truncated file is internally consistent.

**`participants-migration`** — checks artifacts, not just the exit code, because the job records a
per-study data problem as a study-level failure and continues, and skips unmatched ids with only a
log warning. Neither is visible in the exit status.

- `readyStudies > 0`, `failedStudies == 0`, `succeeded + failed == ready`
- one `STUDY_MIGRATED` record per success
- one `*_hpds_id_mapping.csv` per succeeded study
- per mapping file: exact header (`old_hpds_id,new_hpds_id,common_dbgap_id`), at least one row,
  integer HPDS ids, no blank ids, and no duplicated `old_hpds_id` (one legacy patient mapped to two
  new ids is the corruption this migration exists to avoid)
- each sstr sub-report validated; a mapping file with fewer rows than its study's sstr subject
  count is warned about, being the visible symptom of silently dropped patients

**`all-concepts-data-generator`** — the report and the files it claims are checked together:
`rowsProcessed > 0`, exactly one output file per consent group with rows, and every listed file
present in S3 (a non-`s3://` path fails outright). Stale files the job removed
(`staleFilesRemoved`) and unmapped patients are warnings.

### Validator Exit Codes

| Code | Meaning              | Jenkins result |
|-----:|----------------------|----------------|
|    0 | clean                | SUCCESS        |
|   10 | clean, with warnings | UNSTABLE       |
|    1 | failed               | FAILURE        |

Validators never abort on the first failure, so one console read shows everything wrong with a run.

> **Note:** `make` collapses every recipe failure to exit 2, so it cannot carry either the ETL
> exit code or the `10` warning signal. The Jenkinsfiles invoke `monitor-runner.sh`,
> `preflight.sh`, and `validate.sh` directly. The `make monitor` and `make validate` targets are
> for local runs, where pass/fail is sufficient.

---

## Requirements

### Jenkins Agent

| Requirement            | Notes                                                                    |
|------------------------|--------------------------------------------------------------------------|
| JDK 25                 | matches `<java.version>` in `pom.xml`                                    |
| Maven wrapper          | `./mvnw`, checked into the repo                                          |
| Docker CLI + socket    | builds the runner image; also needed for the `*IT` suites. As in the pheno environment, Jenkins runs in a podman container with the host's podman socket mounted at `/var/run/docker.sock`; the `jenkins` user must be able to open it |
| Terraform ≥ 1.3        | provisions runners and the participant DB (`common.mk` installs 1.9.8 if missing) |
| AWS CLI v2             | image upload, report sync, SSM, EC2 describe, Secrets Manager describe   |
| `jq`                   | report and sentinel parsing                                              |
| `python3`              | the migration pre-flight parses the study-list CSV with its `csv` module |
| `bdc-etl-jenkins-role` | the agent's own role; it does all AWS work as this role (no profiles)    |

### Jenkins Plugins

| Plugin                | Used for                                                                                           |
|-----------------------|----------------------------------------------------------------------------------------------------|
| Pipeline              | declarative pipelines                                                                              |
| Pipeline: Basic Steps | the `unstable` step                                                                                |
| JUnit                 | surefire and failsafe reports                                                                      |
| Copy Artifact         | pulling a downstream job's reports onto the orchestrator build (optional — guarded by `try/catch`) |

---

## AWS Setup

Account **515157839325**, region `us-east-1`. Shared settings are in
[`etl-runners/environments/development.tfvars`](../etl-runners/environments/development.tfvars);
every provider sets `allowed_account_ids`, so an apply against the wrong account fails fast.

### 1. Participant DB Secret

There is no standing database secret. `participant-db-start` creates
`hpds-etl-development-participant-db` (the environment's `db_secret_id`), the DB instance writes
its value once Postgres has restored, and `participant-db-stop` deletes it. Details in
[PARTICIPANT_DB.md](PARTICIPANT_DB.md#credentials).

### 2. IAM

`bdc-etl-jenkins-role` is the instance profile for every runner and the participant DB, and the
Jenkins agent's role. It needs:

- **S3** read/write on `bdc-etl-data-d0d6191`: `etl-runner/*` (image tarballs, logs, reports,
  DB boot sentinels), `tf_backend/*` (Terraform state), and `avillach-73-bdcatalyst-etl/*`
  (job inputs and outputs, mapping handoffs, participant DB dumps); plus `s3:ListBucket`,
  `s3:DeleteObject` (the generator's stale-file removal), and `s3:GetBucketVersioning` (the
  generator's pre-flight). Versioning must be **Enabled** on `bdc-etl-data-d0d6191`.
- **SSM**: `AmazonSSMManagedInstanceCore` for the instances; `ssm:SendCommand`,
  `ssm:GetCommandInvocation`, `ssm:DescribeInstanceInformation` for the agent (log tailing and the
  DB backup)
- **EC2 / IAM for Terraform**: create/describe/terminate instances, `ec2:CreateSecurityGroup`,
  `ec2:AuthorizeSecurityGroupIngress`/`Egress`, `ec2:DeleteSecurityGroup`, describe
  subnets/VPCs/AMIs; `iam:CreateInstanceProfile`/`DeleteInstanceProfile`/
  `AddRoleToInstanceProfile`/`RemoveRoleFromInstanceProfile`, `iam:PassRole` on itself, and
  `iam:PutRolePolicy`/`DeleteRolePolicy`/`GetRolePolicy` on itself — the DB stack attaches a
  temporary secret-access policy for as long as the database is up
- **Secrets Manager**: `CreateSecret`, `DeleteSecret`, `DescribeSecret`, `TagResource` on
  `hpds-etl-*` secrets (Get/Put on the live secret is granted by the DB stack itself)
- **STS**: `sts:AssumeRole` on the NHLBI exchange roles
  (`arn:aws:iam::714862078411:role/nih-nhlbi-TopMed-EC2Access-S3` and its 600168050588 twin),
  which trust this role

A minimal runner policy is in
[`terraform-modules/etl-runner/README.md`](../terraform-modules/etl-runner/README.md).

### 3. Networking and AMI

Aligned with the pheno ETL environment (`avillach-jenkins-bdc-etl`), which runs in the same
account, VPC, and bucket:

| Setting                  | Value                                                                             |
|--------------------------|-----------------------------------------------------------------------------------|
| VPC                      | `vpc-0fcb0b3dc2167e8b4`                                                           |
| Subnet                   | `subnet-03a30d72bd12478fc` (private; the pheno hpds-ingest runners' subnet), runners and DB alike |
| Runner security group    | `sg-0932143f21f7c533b`                                                            |
| AMI                      | newest `srce-rhel9-golden*` owned by `752463128620` (SRCE RHEL9 golden image)     |
| Container runtime        | podman, enabled by `ENABLE_PODMAN=true` in `/opt/srce/startup.config`             |

Every bootstrap starts the way the pheno hosts do: write `/opt/srce/startup.config`, run
`/opt/srce/scripts/start-gsstools.sh`, `dnf -y update`. As in pheno's hpds-ingest, the image is
built and saved on the Jenkins agent (`docker buildx build`, `docker save | gzip`, upload to S3) and the
runner `podman load`s and `podman run`s it (reports mounted with `:Z`, so SELinux stays
enforcing). The participant DB installs Postgres from the RHEL9 `postgresql:<pg_version>` module
stream and adds an nftables rule for 5432, since the golden image's firewall drops unlisted
inbound ports.

The subnet must have an S3 path (gateway endpoint or NAT) and reach SSM. The participant DB gets
its own security group allowing 5432 **only** from the runner security group(s), created and
destroyed with the database, so the shared group is never modified.

### Credential Handling

Credentials never reach Jenkins. The DB password is generated on the DB instance and leaves it only
through Secrets Manager: it is never in Terraform state, user data, or the console log. Runners
receive the secret's name, fetch it with their instance role, and write the values to a `600`-mode
file passed as `podman --env-file` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), keeping them out of
the process table and `podman inspect`. `xtrace` is disabled in both bootstraps for the same reason.

---

## Usage

### Full Migration

Run `new-hpds-etl-participant-migration-pipeline` with `MANAGED_INPUTS` and `DATA_ROOT`. Set
`PREFLIGHT_ONLY` to validate the export layout without provisioning anything.

### Permanent Sweep

Run `hpds-etl-pipeline` with `STUDY_ID` blank and `MANAGED_INPUTS` set. Every study marked
"Data is ready to process" = Yes and not yet "Data Processed" is loaded, one ephemeral runner each,
sequentially. Each study's SSTR is discovered under `{DATA_ROOT}/{study_id}/rawData/` as
`sstr_{study_id}.{v}.txt` (case-insensitive; `BDC-ingestion-only__sstr_*` also accepted) — the
same rule `participants-migration` uses. Its per-study allConcepts inputs are
`{DATA_ROOT}/{study_id}/decoded_data/` and `{DATA_ROOT}/{study_id}/mappings/mapping2.csv`
(`DECODED_DATA_DIR` / `CONCEPT_MAPPING_FILE`). A study missing any of the three fails the build
before anything is provisioned. `CONTINUE_ON_STUDY_FAILURE` (default on) lets one bad study fail without stopping the
rest; the build ends with a per-study summary table.

### Single-Study Reload

Run `hpds-etl-pipeline` with `STUDY_ID` set. `MANAGED_INPUTS` is still required (the study's
abbreviation comes from it, and the global AllConcepts and VCF jobs read it); `INPUT` overrides
SSTR discovery. The ready/processed flags are ignored in this mode, so an explicit reload is not
blocked by a sweep flag.

A reload is safe: purge and load share one transaction, so a failure leaves the participant DB
exactly as it was. Expect `participantsInserted = 0` and an UNSTABLE build, which is correct for a
reload.

### Running a Runner Standalone

A DB-backed runner job (`sstr-populate-rds-participants`, `participants-migration`,
`split-allconcepts`, `generate-global-all-concepts`, `create-vcf-indexes`,
`all-concepts-data-generator`) run on its own needs the participant DB up: run
`participant-db-start` first and `participant-db-stop` afterwards. Without it, `require-db.sh`
fails the build before provisioning. For a manual single-study SSTR load, the per-study checks are
job parameters on `sstr-populate-rds-participants`: `EXPECTED_CONSENT_CODES`,
`EXPECTED_MIN_PARTICIPANTS`, and `INSTANCE_TYPE`.

`studies.tsv` has been removed: nothing ever read it (the orchestrator reads managed inputs), so
the per-study expectations and instance types it held were never applied.

### Local Run

```bash
# The participant DB must be up (participant-db-start, or make -C etl-runners/participant-db
# init apply wait-ready). ENV defaults to development.
cd etl-runners/sstr-populate-rds-participants

export TF_VAR_run_id=local-1 TF_VAR_study_id=phs001412 \
       TF_VAR_input_uri=s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/…/sstr_phs001412.v1.txt \
       TF_VAR_name_suffix=local1

make preflight
make jar package          # build, containerise, upload
make init run             # provision; exits with the job's own code
make fetch-reports validate
make clean                # destroy state (the instance already terminated itself)
```

---

## Job Enablement

Jobs are opt-in. Each carries
`@ConditionalOnProperty("etl.jobs.<job-name>.enabled", havingValue = "true")`, so a job whose
flag is absent or `false` is never instantiated and never reaches `JobRegistry`.
[`application.yml`](../src/main/resources/application.yml) is therefore the single list of what
an environment may run.

| Job                                             | Default | Environment override                                            |
|-------------------------------------------------|---------|-----------------------------------------------------------------|
| `template`                                      | `false` | `ETL_JOB_TEMPLATE_ENABLED`                                      |
| `sstr-populate-rds-participants`                | `true`  | `ETL_JOB_SSTR_POPULATE_RDS_PARTICIPANTS_ENABLED`                |
| `single-consent-data-populate-rds-participants` | `true`  | `ETL_JOB_SINGLE_CONSENT_DATA_POPULATE_RDS_PARTICIPANTS_ENABLED` |
| `generate-global-all-concepts`                  | `true`  | `ETL_JOB_GENERATE_GLOBAL_ALL_CONCEPTS_ENABLED`                  |
| `all-concepts-data-generator`                   | `true`  | `ETL_JOB_ALL_CONCEPTS_DATA_GENERATOR_ENABLED`                   |
| `create-vcf-indexes`                            | `true`  | `ETL_JOB_CREATE_VCF_INDEXES_ENABLED`                            |
| `merge-allconcepts`                             | `true`  | `ETL_JOB_MERGE_ALLCONCEPTS_ENABLED`                             |
| `generate-identity-consent-mapping`             | `true`  | `ETL_JOB_GENERATE_IDENTITY_CONSENT_MAPPING_ENABLED`             |
| `participants-migration`                        | `true`  | `ETL_JOB_PARTICIPANTS_MIGRATION_ENABLED`                        |
| `split-allconcepts`                             | `true`  | `ETL_JOB_SPLIT_ALLCONCEPTS_ENABLED`                             |

Running a disabled job exits `5` (`CONFIG_ERROR`) with a message naming the flag.

Notes:

- `participants-migration` also requires `etl.jobs.sstr-populate-rds-participants.enabled`,
  because it injects that job to load the sstr-backed studies. Both flags are on its condition,
  so disabling the sstr job removes the migration job cleanly instead of breaking Spring context
  startup on a missing bean.
- This is the retirement path for the migration: set the two migration flags to `false`
  everywhere, confirm nothing calls them, then delete the jobs, their runner directories, and
  `/Jenkinsfile.migration`.

---

## Concurrency

There is **one participant database per environment**. `participant-db-start` refuses to start a
second while the environment's Terraform state still holds an instance, so two pipelines cannot
silently share or replace it; the second fails at its `Start participant DB` stage, and its
`post` does not touch the first pipeline's database.

Study loads are **sequential by default** (`PARALLEL_STUDY_LOADS`, default `false`, on the
permanent orchestrator). Parallel loads are correct but rarely worth it. Study scoping alone does
not make them safe: every SSTR load writes `participants` with `source = "DBGap"`, so two studies
containing the same `dbgap_subject_id` compete for that subject's HPDS id.

`ParticipantRepository.resolveOrCreate` is what makes concurrent loads correct:

- It re-reads after inserting and returns the id **actually stored**, so a run whose insert lost
  the race cannot write consents and samples against an id it never got. `ON CONFLICT DO NOTHING`
  reports the loser's insert as "0 rows" without revealing the winner, and there are no foreign
  keys from `consents`/`samples` back to `participants`, so nothing else would catch it.
- Inserts are issued in sorted `source_id` order, so two runs inserting an overlapping set of new
  subjects cannot deadlock by acquiring them in opposite orders.

`SstrPopulateRdsParticipantsConcurrencyIT` covers all three properties: shared subjects converge
on one id, no consent or sample row references an id with no participant, and opposing insert
orders do not deadlock.

Loads that share subjects serialize on those rows anyway — the loser waits for the winner's
transaction to commit — so parallelism buys least where studies overlap most, and sequential keeps
the participant DB load predictable and the console log readable.

The migration's `Split AllConcepts` stage runs its studies in **parallel**: the split job only
reads the participant DB (consents), so there is nothing to race on. The DB's `max_connections`
(300) is sized for that fan-out.

---

## Directory Layout

```
Jenkinsfile                     permanent orchestrator
Jenkinsfile.migration           migration orchestrator (TEMPORARY)
terraform-modules/etl-runner/   shared self-terminating-runner module
etl-runners/
├─ Dockerfile                   one image for every job (the JAR selects the job at runtime)
├─ run-job.sh                   container entrypoint: env vars to --flags, java -jar, exit code
├─ common.mk                    shared build/deploy/monitor targets
├─ environments/
│  └─ development.tfvars        account, VPC, security group, instance role, DB secret name
├─ common/
│  ├─ lib.sh                    check/soft/fail/warn/summary assertion helpers
│  ├─ monitor-runner.sh         polls for the sentinel; exits with the job's exit code
│  ├─ require-db.sh             fails fast on the agent when the participant DB is down
│  └─ validate-report.sh        assertions true of every JobResult report
├─ participant-db/              SHARED: the per-pipeline Postgres server
│  ├─ Jenkinsfile.start  Jenkinsfile.stop  Makefile  wait-ready.sh  backup-via-ssm.sh
│  └─ terraform/                instance, SG, secret, user_data.sh.tpl, backup.sh.tpl
├─ participants-migration/            TEMPORARY
├─ split-allconcepts/                 TEMPORARY
├─ sstr-populate-rds-participants/    PERMANENT
├─ generate-global-all-concepts/      PERMANENT
├─ create-vcf-indexes/                PERMANENT
├─ merge-allconcepts/                 PERMANENT (no DB)
├─ all-concepts-data-generator/       PERMANENT (per study, from the permanent orchestrator)
└─ generate-identity-consent-mapping/ PERMANENT, standalone (no DB)
   each runner: Jenkinsfile  Makefile  preflight.sh  validate.sh  terraform/
```

---

## Operations

### Artifact Locations

| Artifact            | Location                                                                                    |
|---------------------|---------------------------------------------------------------------------------------------|
| Console log         | the Jenkins build                                                                           |
| JSON reports        | archived on the build, and `s3://bdc-etl-data-d0d6191/etl-runner/reports/<job>/<run-id>/`   |
| Runner log          | `s3://bdc-etl-data-d0d6191/etl-runner/logs/<job>-<run-id>.log` (bootstrap plus container output) |
| Completion sentinel | `status.json` under the report prefix                                                       |
| Terraform state     | `s3://bdc-etl-data-d0d6191/tf_backend/etl-runners/hpds-etl/…`                               |
| Participant DB dumps| `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<env>/backups/`        |

### Diagnosing a Failure

1. **Exit code** — identifies which of the five categories the failure falls in.
2. **`status.json`** — its `phase` field distinguishes a bootstrap failure (`install`,
   `credentials`, `image`) from the job itself (`job`). `credentials` with exit 5 usually means
   the participant DB was not running.
3. **JSON report** — `inputValidation` and `outputValidation` issues name the specific rows and
   columns.

### Timeouts

The monitor gives up at `JOB_TIMEOUT_SECONDS` (default 7200) and exits 124. The instance still
terminates itself. Check the tail of the runner log in S3 for the last phase reached.

### Leaked Instance Profile

`terraform destroy` runs in `post { always }`. If a build is hard-killed, the instance profile
`<job>-etl-instance-profile-<suffix>` can survive; the instance itself always terminates, so no
compute is billed. Delete the profile manually or re-run `make destroy` with the same `STATE_KEY`.

### Participant DB Left Running

If an orchestrator ends with "participant-db-stop … ended FAILURE", or a later start says a
database is already running, the instance is still up and may hold writes no dump has captured.
Re-run `participant-db-stop`; it backs up before destroying. See
[PARTICIPANT_DB.md](PARTICIPANT_DB.md#recovery-and-troubleshooting).

### Cost

One instance per job run, alive only for the duration of the job, plus the participant DB for the
duration of the pipeline. Per-build image tarballs are removed from S3 in `post { always }`.

---

## Known Limitations

- **No post-load assertion against the database itself.** Everything after the test suites reasons
  about report metrics, which are counts of rows the repositories affected rather than a `SELECT`
  against the loaded table. The fix is a `verify-participants` job in the JAR, run as a second
  container on a runner, since only a runner can reach the participant DB.
- **SSTR reports cannot confirm their own study.** `SstrPopulateRdsParticipantsJob.report` emits no
  `studyId` metric (split and all-concepts-data-generator do); adding one would let `validate.sh`
  confirm a report belongs to the study it was asked about instead of inferring it from the run id.
- **`instance_type` is an estimate per job.** The SSTR and migration jobs hold their input in
  memory, so the ceiling is the largest file rather than the average. Override with the job's
  `INSTANCE_TYPE` parameter. This now takes effect: `instance_type` was removed from every runner's
  `.tfvars`, because a `-var-file` value outranks `TF_VAR_instance_type` and was silently
  overriding the parameter.
- **Parameter changes need one run to register.** Jenkins only learns a Jenkinsfile's new
  `parameters` block after a build of it runs; until then it serves the old defaults (and Rebuild
  copies them forward). Run each changed job once (`PREFLIGHT_ONLY=true` where available) after
  merging parameter changes — including the new `ENV = development` choice and the removal of
  `CONTAINER_ASSUME_ROLE_ARN`.

---

## References

- [`docs/PARTICIPANT_DB.md`](PARTICIPANT_DB.md) — the participant database lifecycle
- [`terraform-modules/etl-runner`](../terraform-modules/etl-runner/README.md) — the runner module
- [`etl-runners/README.md`](../etl-runners/README.md) — runner directory conventions
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) — adding a job and its runner
- [`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — the pattern this follows
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
