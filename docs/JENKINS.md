# Jenkins Pipelines and Ephemeral ETL Runners

This document describes how the hpds-etl jobs are orchestrated: two Jenkins pipelines, one
ephemeral EC2 runner per job, provisioned with Terraform and torn down after each run, and a
long-lived participant database on the Jenkins host that every pipeline run makes sure is up and
backs up at its end. Jobs are selected at runtime from a single fat JAR and communicate outcome
through process exit codes.

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
| `participant-db-backup`                       | `etl-runners/participant-db/Jenkinsfile.backup`             |
| `participants-migration`                      | `etl-runners/participants-migration/Jenkinsfile`            |
| `split-allconcepts`                           | `etl-runners/split-allconcepts/Jenkinsfile`                 |
| `sstr-populate-rds-participants`              | `etl-runners/sstr-populate-rds-participants/Jenkinsfile`    |
| `generate-global-all-concepts`                | `etl-runners/generate-global-all-concepts/Jenkinsfile`      |
| `create-vcf-indexes`                          | `etl-runners/create-vcf-indexes/Jenkinsfile`                |
| `merge-allconcepts`                           | `etl-runners/merge-allconcepts/Jenkinsfile`                 |
| `generate-allconcepts-manifest`               | `etl-runners/generate-allconcepts-manifest/Jenkinsfile`     |
| `qa-consent-counts`                           | `etl-runners/qa-consent-counts/Jenkinsfile`                 |
| `all-concepts-data-generator`                 | `etl-runners/all-concepts-data-generator/Jenkinsfile`       |
| `generate-identity-consent-mapping`           | `etl-runners/generate-identity-consent-mapping/Jenkinsfile` |

`all-concepts-data-generator` is triggered per unprocessed study by the permanent
orchestrator's `Generate per-study AllConcepts` stage, and can also be run by hand.
`generate-identity-consent-mapping` is standalone — no orchestrator triggers it; run it when a
new DMC harmonization drop lands (ALS-12727).

### `qa-consent-counts` Parameters

The last stage of both orchestrators, and runnable by hand (it needs the participant DB up). Each
parameter maps to the job's `--` flag; both orchestrators pass all of them explicitly, with the
defaults below. See [PERMANENT_PIPELINE.md](PERMANENT_PIPELINE.md#stage-11-qa-consent-counts).

| Parameter                       | Default                                               | Description |
|---------------------------------|-------------------------------------------------------|-------------|
| `MANAGED_INPUTS`                | (blank)                                               | `--managed-inputs`: its ready studies are checked, by `Study Identifier` and `Version`. Blank: the configured `etl.managed-inputs.uri`. The orchestrators pass their later-stage copy (excluded studies marked not ready) |
| `LOAD_COUNTS`                   | (blank)                                               | `--load-counts`: CSV `study_id,consent_code,participants_loaded[,source]` of this run's loads; blank skips the load-report check. Permanent: built from the SSTR reports; migration: `participants_migration_consent_counts.csv` |
| `STUDY_FILTER`                  | (blank)                                               | `--study-filter`: comma-separated study ids; blank checks every ready study |
| `CONCURRENCY`                   | `4`                                                   | Studies looked up in dbGaP at once, 1-16 (each call takes ~10 s) |
| `DBGAP_BASE_URL`                | `https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study`  | `{phs}.{v}/summary` is appended |
| `DBGAP_MAX_ATTEMPTS`            | `5`                                                   | Requests per study, retries included, 1-20 |
| `DBGAP_INITIAL_BACKOFF_SECONDS` | `2`                                                   | Wait after the first failure, doubling per retry, 0-600 |
| `DBGAP_MAX_BACKOFF_SECONDS`     | `60`                                                  | Longest wait, 1-3600; a `Retry-After` header is honoured instead (up to 5 minutes) |
| `DBGAP_TIMEOUT_SECONDS`         | `60`                                                  | Per-request timeout, 1-600 |
| `DBGAP_GIVE_UP_AFTER`           | `3`                                                   | Stop asking dbGaP once this many studies in a row got no usable answer, 1-1000; the rest are `UNVERIFIED` and the job exits 4 in minutes rather than retrying every study |
| `DBGAP_DEADLINE_SECONDS`        | `5400`                                                | No lookup starts after this many seconds, 1-86400; keep it well inside `JOB_TIMEOUT_SECONDS`, or a slow dbGaP ends in a monitor timeout (exit 124) with no report |
| `INSTANCE_TYPE`                 | `t3.medium`                                           | Runner instance type |
| `JOB_TIMEOUT_SECONDS`           | `7200`                                                | Monitor timeout (exit 124) |
| `RUN_ID`, `PREBUILT_IMAGE_TAR`, `SKIP_TESTS`, `RETRY_ON_INFRA`, `ENV` | | As for every runner job; `RETRY_ON_INFRA` reruns it once after exit 4 (dbGaP still down after its retries) |

Discrepancies make the build UNSTABLE (validate.sh exit 10); dbGaP not answering for a study after
its retries fails it (exit 4), with the report and both CSVs still archived.

> **Folder names must not contain a space.** `common.mk` uses unquoted workspace paths, so a job
> in a folder such as `__Harmonization Work` breaks at `ensure-terraform` (identity-mapping
> shakeout run #2). `hpds-etl` is safe; keep every job, `generate-identity-consent-mapping`
> included, inside it.

If your naming differs, change the orchestrator's job-name parameter rather than the pipeline.

---

## Architecture

### Orchestration

The DAG lives in Jenkins, not in the JAR. Each orchestrator stage triggers that job's own
pipeline, which owns its runner end to end. After the gate, the orchestrator makes sure the
long-lived participant database is up; `post { always }` backs it up to S3. It is never stopped.
The permanent orchestrator's stage logic is in `etl-runners/common/orchestrator.groovy`, which
`/Jenkinsfile` `load`s in its first stage.

```
/Jenkinsfile  (or /Jenkinsfile.migration)
  Build ▸ Tests                              gate: nothing is provisioned until these pass
  ▸ (Resolve studies)                        permanent: parallel S3 probe; sweeps exclude studies missing inputs
  ▸ Package runner image                     one PREBUILT_IMAGE_TAR for every runner build
  ▸ Start participant DB                     build job: participant-db-start (DB stays up between runs)
  └─ stage 'Load SSTR participants'          (one stage per job, in DAG order)
       └─ build job: sstr-populate-rds-participants    etl-runners/<job>/Jenkinsfile
            Init ▸ Build JAR ▸ Pre-flight ▸ Package image
            ▸ Provision (require-db.sh, terraform apply) ▸ Monitor ▸ Fetch reports ▸ Validate
            post: terraform destroy, archive reports
  └─ last stage 'QA consent counts'          build job: qa-consent-counts (DB and loads vs dbGaP; read-only)
  post { always }: build job: participant-db-backup    PROMOTE_BACKUP = build SUCCESS/UNSTABLE, or QA reached (DB stays up)
                   pipeline-report / migration-report  downstream-artifacts/<report>/ + S3; QA discrepancies first
```

Build and test run once in the orchestrator as the gate for the whole run. Downstream jobs are
invoked with `SKIP_TESTS=true` so the same commit's suites are not re-run per study. Runner
jobs go further: each orchestrator builds the runner image once and passes it as
`PREBUILT_IMAGE_TAR`, so those builds (SSTR load, allConcepts generation, large splits) build
nothing on the Jenkins host; smaller splits run on a runner pool with no per-study build at all.
The permanent orchestrator passes it to every runner job it calls (SSTR load, global AllConcepts,
VCF indexes, per-study allConcepts, merge, manifest and QA consent counts).

The orchestrator backs up only a database its own start succeeded on (`DB_STARTED`): a failed
start changed nothing worth saving, and is never recorded as a run's backup. The participant DB
lifecycle, dump location, and recovery are in [PARTICIPANT_DB.md](PARTICIPANT_DB.md).

The permanent orchestrator's end-of-run report, timeouts, missing-inputs exclusion and generator
scheduling are described in [PERMANENT_PIPELINE.md](PERMANENT_PIPELINE.md#end-of-run-report).

### Runner Lifecycle

```
Jenkins agent                          ephemeral EC2 (self-terminating)
─────────────                          ────────────────────────────────
./mvnw package        ─ target/hpds-etl.jar
docker buildx build   ─ hpds-etl-runner image
docker save | gzip    ─▶ s3://<stack>/etl-runner/container/<run>.tar.gz
require-db.sh         ─ DB-backed runners only: fail fast if the participant DB is down
terraform apply       ─▶ launch instance ──▶ user_data:
  (only the SSM parameter's                   read participant DB credentials from SSM
   name, never a credential)                    (instance role) → DB_URL (sslmode=require) /
                                                DB_USERNAME / DB_PASSWORD (skipped for DB-free jobs)
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
leaves `uses_participant_db` at its default `false`, so the credential step is skipped and they
run whether or not the participant DB is up.

Properties of this model:

- **One principal.** Runners and the Jenkins agent (whose host also runs the participant DB
  container) all run as `bdc-etl-jenkins-role` in account 515157839325, which also owns the data. No cross-account role
  is assumed. The only remaining assume is `generate-identity-consent-mapping`'s in-process
  `--role-arn` (the NHLBI exchange role), made by the instance role.
- **No SSH.** Access is AWS SSM Session Manager only; no instance has a key pair.
- **The participant DB lives on the Jenkins host**, as a long-lived PostgreSQL 16 container
  started through the host's podman socket. Runners get its credentials from SSM at boot, not
  from the Jenkins node (see [Credential Handling](#credential-handling) and
  [PARTICIPANT_DB.md](PARTICIPANT_DB.md)).
- **Self-terminating runners.** A runner terminates whether the job succeeded, failed, was
  OOM-killed, or had its spot capacity reclaimed.
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

A DB-backed runner that cannot read usable credentials from the SSM parameter (no
`participant-db-start` yet, or missing `ssm:GetParameter` / `kms:Decrypt`) exits `5` in its
`credentials` phase; one whose credentials are published but whose database is down fails when the
job connects. `common/require-db.sh` normally catches both on the agent first, in seconds, before
anything is provisioned: it reads the parameter and logs in with it.

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
included; stages without a pre-flight mode (global AllConcepts, merge, manifest) are skipped.

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
zero input- and output-validation errors, no `errorMessage`. Validation warnings, or an exit code of
`SUCCESS_WITH_WARNINGS`, are reported as warnings: exit 10, so the build is UNSTABLE.

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

- `readyStudies > 0`, `failedStudies == 0`, `succeeded + skipped + failed == ready`
- `skippedStudies == 0` is a warning, not a failure: a study with no participant files, or one
  flagged `STUDY_NEEDS_DATA_REVIEW` (its patient mapping resolved to none of its loaded
  subjects), is listed and left out of the later stages, and the build is UNSTABLE
- one `STUDY_MIGRATED` record per success
- one `*_hpds_id_mapping.csv` per succeeded or needs-review study
- per mapping file: exact header (`old_hpds_id,new_hpds_id,common_dbgap_id`), at least one row
  (exactly zero for a study flagged `STUDY_NEEDS_DATA_REVIEW` in the report),
  integer HPDS ids, no blank ids, and no duplicated `old_hpds_id` (one legacy patient mapped to two
  new ids is the corruption this migration exists to avoid)
- each sstr sub-report validated; a mapping file with fewer rows than its study's sstr subject
  count is warned about, being the visible symptom of silently dropped patients

**`all-concepts-data-generator`** — the report and the files it claims are checked together:
`rowsProcessed > 0`, exactly one output file per consent group with rows, and every listed file
present in S3 (a non-`s3://` path fails outright). Stale files the job removed
(`staleFilesRemoved`) and unmapped patients are warnings, and so is a missing mapped data file,
which skips stale cleanup for the whole study (`STALE_CLEANUP_SKIPPED`, `staleFilesKept`).

**`create-vcf-indexes`** — a consent group held back for bad data (`ID_COUNT_MISMATCH`,
`BAD_STUDY_ABV`) is an error: it is not written, and its previously published files are deleted
(`metrics.heldBackGroups[].deletedFiles`, `metrics.heldBackFilesDeleted`); `validate.sh` lists
each one with what was deleted.

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
| Terraform ≥ 1.3        | provisions runners (`common.mk` installs 1.9.8 if missing)                |
| AWS CLI v2             | image upload, report sync, SSM, EC2 describe                             |
| `jq`                   | report and sentinel parsing                                              |
| `python3`              | the migration pre-flight parses the study-list CSV with its `csv` module; the end-of-run reports, and the QA's load-counts builder and validator |
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

### 1. Participant DB Credentials

`participant-db-start` generates the app role's password, rotating it on every start, and
publishes the connection details as the SSM SecureString
`/hpds-etl/<ENV>/participant-db/app-credentials`. Each DB-backed runner reads it at boot, and
connections require TLS. Details in [PARTICIPANT_DB.md](PARTICIPANT_DB.md#credentials).

### 2. IAM

One role, `bdc-etl-jenkins-role`, does everything: the Jenkins server (which also hosts the
participant DB container) runs as it, and every runner attaches it through the pre-existing instance profile
`bdc-etl-jenkins-temp-profile` (`iam_instance_profile_name` in `environments/<ENV>.tfvars`). The
code creates no IAM resources, security groups, or secrets at runtime. These are the **minimum**
permissions it needs, derived from every AWS call the Jenkinsfiles, scripts, Terraform, and JAR make.

**Used by the Jenkins server** (Terraform, monitoring, DB backup and credentials):

| IAM console service | Actions | Resource | Why |
|---|---|---|---|
| EC2 | `ec2:Describe*` | `*` | Terraform's lookups and refreshes (AMI, subnet, VPC, instance, volumes, ENIs, instance types, attributes); `require-db.sh`, `wait-ready.sh`, `monitor-runner.sh`, the start job's reuse check |
| EC2 | `ec2:RunInstances` | `*` | Launching every runner |
| EC2 | `ec2:CreateTags` | `*`, condition `ec2:CreateAction = RunInstances` | Tags applied at launch |
| EC2 | `ec2:TerminateInstances` | instances tagged `ManagedBy=terraform` | `terraform destroy` of the runners |
| EC2 | `ec2:ModifyInstanceAttribute` | instances tagged `ManagedBy=terraform` | The AWS provider re-applies `instance_initiated_shutdown_behavior` right after every launch that sets it (the runners' `terminate`), even though `RunInstances` already set it |
| IAM | `iam:PassRole` | `bdc-etl-jenkins-role` | Launching instances with `bdc-etl-jenkins-temp-profile` |
| Systems Manager | `ssm:SendCommand` | the instances **and** `document/AWS-RunShellScript` (both required) | Runner log tailing |
| Systems Manager | `ssm:GetCommandInvocation` | `*` (not resource-scopable) | Reading those commands' results |
| Systems Manager | `ssm:PutParameter`, `ssm:GetParameter` | `parameter/hpds-etl/*/participant-db/app-credentials` | `participant-db-start` publishes the participant DB credentials (and reads them back to check a kept database's password); `require-db.sh` reads them before every DB-backed runner job |
| KMS | `kms:Encrypt`, `kms:GenerateDataKey`, `kms:Decrypt` | the parameter's key, condition `kms:ViaService = ssm.us-east-1.amazonaws.com` | The SecureString's encryption. Already allowed through SSM for the AWS-managed `alias/aws/ssm` key; needed explicitly (plus a key policy allowing the role) for a customer-managed `SSM_KMS_KEY_ID` |
| S3 | see below | `bdc-etl-data-d0d6191` | Terraform state (`tf_backend/`), image upload, reports, logs, sentinels, participant DB dumps and restores (`participant-db-start` / `-backup` run on the Jenkins server) |

**Used by the runner instances** (and, for S3, by the Jenkins server's participant DB jobs):

| IAM console service | Actions | Resource | Why |
|---|---|---|---|
| S3 | `s3:ListBucket`, `s3:ListBucketVersions`, `s3:GetBucketVersioning` | `bdc-etl-data-d0d6191` | Listings and `head-bucket`; the merge and manifest jobs' version listings; their, the DB backup's (and generator pre-flight's) versioning check |
| S3 | `s3:GetObject`, `s3:GetObjectVersion`, `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts` | `bdc-etl-data-d0d6191/*` | Images, dumps and restores, reports, logs, job inputs and outputs, stale-file removal, large multipart uploads. `GetObjectVersion`: the merge reads (and the merge and manifest head) specific object versions |
| Systems Manager | `ssm:GetParameter` | `parameter/hpds-etl/*/participant-db/app-credentials` | DB-backed runners read the participant DB credentials at boot |
| KMS | `kms:Decrypt` | the parameter's key, condition `kms:ViaService = ssm.us-east-1.amazonaws.com` | Decrypting that SecureString (see the Jenkins-server KMS row) |
| Managed policy | `AmazonSSMManagedInstanceCore` (attach it; it is not in the service list) | | Registers the instances with SSM, without which `SendCommand` has nothing to deliver to |
| STS | `sts:AssumeRole` | the NHLBI exchange roles | `generate-identity-consent-mapping` only; those roles must also trust `bdc-etl-jenkins-role` |

S3 versioning must be **Enabled** on `bdc-etl-data-d0d6191`: `participant-db-start` and
`participant-db-backup` refuse to overwrite a dump otherwise. If the root volumes are encrypted with a customer-managed KMS
key, launching also needs `kms:CreateGrant`, `kms:GenerateDataKeyWithoutPlaintext`, `kms:Decrypt`,
and `kms:DescribeKey` on it.

The whole policy (attach `AmazonSSMManagedInstanceCore` alongside it):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "Ec2Read", "Effect": "Allow", "Action": "ec2:Describe*", "Resource": "*" },
    { "Sid": "Ec2Launch", "Effect": "Allow", "Action": "ec2:RunInstances", "Resource": "*" },
    { "Sid": "Ec2TagOnLaunch", "Effect": "Allow", "Action": "ec2:CreateTags", "Resource": "*",
      "Condition": { "StringEquals": { "ec2:CreateAction": "RunInstances" } } },
    { "Sid": "Ec2ManageOwnInstances", "Effect": "Allow",
      "Action": ["ec2:TerminateInstances", "ec2:ModifyInstanceAttribute"],
      "Resource": "arn:aws:ec2:us-east-1:515157839325:instance/*",
      "Condition": { "StringEquals": { "ec2:ResourceTag/ManagedBy": "terraform" } } },
    { "Sid": "PassOwnRole", "Effect": "Allow", "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::515157839325:role/bdc-etl-jenkins-role" },
    { "Sid": "SsmRunCommands", "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:us-east-1:515157839325:instance/*",
        "arn:aws:ssm:us-east-1::document/AWS-RunShellScript"
      ] },
    { "Sid": "SsmReadCommandResults", "Effect": "Allow", "Action": "ssm:GetCommandInvocation",
      "Resource": "*" },
    { "Sid": "ParticipantDbCredentials", "Effect": "Allow",
      "Action": ["ssm:PutParameter", "ssm:GetParameter"],
      "Resource": "arn:aws:ssm:us-east-1:515157839325:parameter/hpds-etl/*/participant-db/app-credentials" },
    { "Sid": "ParticipantDbCredentialsKey", "Effect": "Allow",
      "Action": ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"],
      "Resource": "<arn of the parameter's KMS key: aws/ssm's, or SSM_KMS_KEY_ID>",
      "Condition": { "StringEquals": { "kms:ViaService": "ssm.us-east-1.amazonaws.com" } } },
    { "Sid": "S3Bucket", "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:ListBucketVersions", "s3:GetBucketVersioning"],
      "Resource": "arn:aws:s3:::bdc-etl-data-d0d6191" },
    { "Sid": "S3Objects", "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject",
                 "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
      "Resource": "arn:aws:s3:::bdc-etl-data-d0d6191/*" },
    { "Sid": "AssumeNhlbiExchangeRoles", "Effect": "Allow", "Action": "sts:AssumeRole",
      "Resource": [
        "arn:aws:iam::714862078411:role/nih-nhlbi-TopMed-EC2Access-S3",
        "arn:aws:iam::600168050588:role/nih-nhlbi-TopMed-EC2Access-S3"
      ] }
  ]
}
```

Notes:

- Every instance this code launches is tagged `ManagedBy=terraform`, which is what scopes
  `TerminateInstances` and `ModifyInstanceAttribute` above.
- Any job pointed at a bucket other than `bdc-etl-data-d0d6191` needs that bucket added to both S3
  statements.
- **Optional, for manual recovery only:** `s3:GetObjectVersion` (restore an older dump by hand).
- **Not needed** (the code no longer does these): Secrets Manager; creating or deleting instance
  profiles; `iam:PutRolePolicy` / `DeleteRolePolicy` / `GetRolePolicy`; creating, deleting, or
  authorizing security groups; `ssm:DescribeInstanceInformation`.

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
`/opt/srce/scripts/start-gsstools.sh`, then install only the packages they need (no `dnf -y update`: the
golden image is rebuilt monthly, and a full update on every boot cost minutes). As in pheno's hpds-ingest, the image is
built and saved on the Jenkins agent (`docker buildx build`, `docker save | gzip`, upload to S3) and the
runner `podman load`s and `podman run`s it (reports mounted with `:Z`, so SELinux stays
enforcing). The participant DB is a PostgreSQL 16 container on the Jenkins host (see
[PARTICIPANT_DB.md](PARTICIPANT_DB.md#prerequisites-on-the-jenkins-host)).

The subnet must have an S3 path (gateway endpoint or NAT) and reach SSM. No security group is
created at runtime. Runners reach the participant DB on the Jenkins host's port 5432 (TLS only), so
the Jenkins host's security group must allow TCP 5432 from the runners' group
(`sg-0932143f21f7c533b`) and the host's nftables must accept it from the runner network only; see
[PARTICIPANT_DB.md](PARTICIPANT_DB.md#prerequisites-on-the-jenkins-host).

#### QA consent counts: network egress

`qa-consent-counts` calls the dbGaP SSTR API from its runner, so **the runner security group
(`sg-0932143f21f7c533b`) and the subnet's route must allow outbound HTTPS (TCP 443) to
`www.ncbi.nlm.nih.gov`** (a public endpoint: it needs a NAT or other internet route; an S3 gateway endpoint does not reach it), besides the
participant DB and S3. Without it every lookup times out, the job exits 4 after its retries, and
each study's rows are `UNVERIFIED`. Its pre-flight probes the API from the Jenkins agent, which
shows the API is up but says nothing about the runner's own egress. To check from a runner subnet:
`curl -s -o /dev/null -w '%{http_code}\n' https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study/phs001412.v4/summary`
should print `200`.

### Credential Handling

The app role's password is generated by `participant-db-start` and **rotated on every start**. It
publishes the connection details as the SSM SecureString
`/hpds-etl/<ENV>/participant-db/app-credentials`. A DB-backed runner (module input
`uses_participant_db = true`, with `environment`) reads that parameter at boot with its instance
role (`ssm:GetParameter`, plus `kms:Decrypt` via SSM; see
[`terraform-modules/etl-runner/README.md`](../terraform-modules/etl-runner/README.md)) and writes the
values to a `600`-mode file passed as `podman --env-file` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`),
keeping them out of the process table and `podman inspect`. Connections to the database require
TLS. `xtrace` is disabled in both bootstraps.

So the password never passes through Terraform variables, EC2 user data or Terraform state, and
is never printed to the Jenkins console; a rotation invalidates whatever an earlier run saw.

---

## Usage

### Full Migration

Run `new-hpds-etl-participant-migration-pipeline` with `MANAGED_INPUTS` and `DATA_ROOT`. Set
`PREFLIGHT_ONLY` to validate the export layout without provisioning anything.

### Permanent Sweep

Run `hpds-etl-pipeline` with `STUDY_ID` blank and `MANAGED_INPUTS` set. Every study marked
"Data is ready to process" = Yes and not yet "Data Processed" is loaded, one ephemeral runner each:
the SSTR loads sequentially (or `STUDY_CONCURRENCY` at once with `PARALLEL_STUDY_LOADS`), the
per-study allConcepts always `STUDY_CONCURRENCY` at once, largest study first, with a timeout
scaled to its decoded data. Each study's SSTR is discovered under `{DATA_ROOT}/{study_id}/rawData/` as
`sstr_{study_id}.{v}.txt` (case-insensitive; `BDC-ingestion-only__sstr_*` also accepted) — the
same rule `participants-migration` uses. Its per-study allConcepts inputs are
`{DATA_ROOT}/{study_id}/decoded_data/` and `{DATA_ROOT}/{study_id}/mappings/mapping2.csv`
(`DECODED_DATA_DIR` / `CONCEPT_MAPPING_FILE`). All three are probed in parallel before anything is
provisioned; a study missing any of them is **excluded** (build UNSTABLE, later stages see it as
not ready), and the build fails only if every study to load is excluded.
`CONTINUE_ON_STUDY_FAILURE` (default on) lets one bad study fail without stopping the
rest; each per-study stage ends with a summary table, and the run with
`downstream-artifacts/pipeline-report/pipeline-report.md` (also in S3 under
`etl-runner/reports/permanent-pipeline/<build-tag>/`), which lists every study's outcome and why.

### Single-Study Reload

Run `hpds-etl-pipeline` with `STUDY_ID` set. `MANAGED_INPUTS` is still required (every later
stage reads it); the SSTR is discovered from the study id under `DATA_ROOT` (the abbreviation is
not used), and `INPUT` overrides that discovery. The ready/processed flags are ignored in this
mode, so an explicit reload is not blocked by a sweep flag; a missing input fails the build, and
the merge is passed `STUDY_IDS=<STUDY_ID>` so it merges exactly that study.

A reload is safe: purge and load share one transaction, so a failure leaves the participant DB
exactly as it was. Expect `participantsInserted = 0` and an UNSTABLE build, which is correct for a
reload.

### Running a Runner Standalone

A DB-backed runner job (`sstr-populate-rds-participants`, `participants-migration`,
`split-allconcepts`, `generate-global-all-concepts`, `create-vcf-indexes`,
`all-concepts-data-generator`) run on its own needs the participant DB up: run
`participant-db-start` first (it leaves the database up; run `participant-db-backup` afterwards
to save the runner's writes). Without it, `require-db.sh`
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
| `generate-global-all-concepts`                  | `true`  | `ETL_JOB_GENERATE_GLOBAL_ALL_CONCEPTS_ENABLED`                  |
| `all-concepts-data-generator`                   | `true`  | `ETL_JOB_ALL_CONCEPTS_DATA_GENERATOR_ENABLED`                   |
| `create-vcf-indexes`                            | `true`  | `ETL_JOB_CREATE_VCF_INDEXES_ENABLED`                            |
| `merge-allconcepts`                             | `true`  | `ETL_JOB_MERGE_ALLCONCEPTS_ENABLED`                             |
| `generate-allconcepts-manifest`                 | `true`  | `ETL_JOB_GENERATE_ALLCONCEPTS_MANIFEST_ENABLED`                 |
| `qa-consent-counts`                             | `true`  | `ETL_JOB_QA_CONSENT_COUNTS_ENABLED`                             |
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

There is **one participant database per environment**, kept up between runs. Nothing locks it,
so **do not run two pipelines at once in the same environment**: they would interleave writes,
and either one's end-of-run backup could capture the other's half-finished work. See
[PARTICIPANT_DB.md](PARTICIPANT_DB.md).

SSTR loads are **sequential by default** (`PARALLEL_STUDY_LOADS`, default `false`, on the
permanent orchestrator); in parallel, at most `STUDY_CONCURRENCY` (default 5) per-study builds run
at once, however many studies are ready. (Per-study allConcepts only reads the DB, so it always
runs `STUDY_CONCURRENCY` at once.) Parallel loads are correct but rarely worth it. Study scoping alone does
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

The migration's `Split AllConcepts` stage runs its studies in **parallel**: the split job only reads
the participant DB (consents), so there is nothing to race on. Studies are sized up front and run
largest first, small ones on `SPLIT_CONCURRENCY` (default 5) long-lived runner-pool workers that the
orchestrator feeds directly (no Jenkins build per study), large ones on their own right-sized
instances, `SPLIT_LARGE_CONCURRENCY` (default 2) at a time. The stage ends with one consolidated
report, `downstream-artifacts/split-allconcepts-report-<build-tag>.md` (see
[MIGRATION_PIPELINE.md](MIGRATION_PIPELINE.md#stage-4-split-allconcepts)). The DB's
`max_connections` (300) is far above that concurrency.

A study whose **data** is the problem (no participant files, a patient mapping that matches none of
its subjects, a missing or rejected allConcepts input) is excluded and the run carries on UNSTABLE;
only a non-data failure (infrastructure, timeout) fails a stage. Whatever the result, the build ends
with `downstream-artifacts/migration-report/` (also in S3 under
`etl-runner/reports/migration-pipeline/<build-tag>/migration-report/`): participant counts per consent
group, each study's status, and every study needing follow-up with why (see
[MIGRATION_PIPELINE.md](MIGRATION_PIPELINE.md#post-migration-report)).

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
│  └─ development.tfvars        account, VPC, security group, instance role
├─ common/
│  ├─ lib.sh                    check/soft/fail/warn/summary assertion helpers
│  ├─ monitor-runner.sh         polls for the sentinel; exits with the job's exit code
│  ├─ db-credentials-path.sh    where the participant DB credentials are saved on Jenkins
│  ├─ require-db.sh             fails fast on the agent when the participant DB is down
│  ├─ validate-report.sh        assertions true of every JobResult report
│  ├─ orchestrator.groovy       the permanent orchestrator's stage logic (loaded by /Jenkinsfile)
│  └─ probe-study-inputs.sh     parallel S3 probe of every study's inputs (Resolve studies)
├─ pipeline-report/             the permanent pipeline's end-of-run report (+ test/ fixture)
├─ participant-db/              SHARED: the long-lived Postgres 16 container on the Jenkins host
│  ├─ Jenkinsfile.start  Jenkinsfile.backup  Makefile  participant-db.conf
│  ├─ lib.sh  start.sh  backup.sh   container, dump/restore, and start-decision logic
│  └─ test/                     run-tests.sh + stub docker/aws
├─ participants-migration/            TEMPORARY
├─ split-allconcepts/                 TEMPORARY
├─ migration-report/                  TEMPORARY: the migration orchestrator's final report (no runner; + test/)
├─ sstr-populate-rds-participants/    PERMANENT
├─ generate-global-all-concepts/      PERMANENT
├─ create-vcf-indexes/                PERMANENT
├─ merge-allconcepts/                 PERMANENT (no DB)
├─ generate-allconcepts-manifest/     PERMANENT (no DB), after the merge in both orchestrators
├─ qa-consent-counts/                 PERMANENT QA (reads the DB, calls dbGaP), last stage of both orchestrators;
│                                     also build-load-counts.py and qa_report.py (the reports' QA section), test/
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
| Permanent run report| `downstream-artifacts/pipeline-report/` on the build, and `s3://<DATA_ROOT bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/pipeline-report/` |
| QA load counts      | `s3://<DATA_ROOT bucket>/etl-runner/reports/{permanent,migration}-pipeline/<build-tag>/qa_load_counts.csv` (the QA's `--load-counts`) |
| Exclusion managed inputs | `s3://<MANAGED_INPUTS bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/managed_inputs.csv` (only when studies were excluded) |
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

The permanent orchestrator passes the generator a timeout scaled to each study's decoded data
(7200-12600 s) and the merge `MERGE_ALLCONCEPTS_TIMEOUT_SECONDS` (at most 28800: the merge job's
9 h build timeout less 1 h for provisioning and teardown; its one retry on exit 4 is capped to what
is left of the 9 h). Its own 72 h
backstop is on a parent `ETL` stage, not in the pipeline's `options {}`, because a pipeline-level
timeout also covers `post {}` and would cut short the DB backup; see
[PERMANENT_PIPELINE.md](PERMANENT_PIPELINE.md#timeouts).

### Participant DB Backup Failed

If an orchestrator ends with "participant-db-backup … ended FAILURE", that run's writes are only in
the live database. Fix the cause and re-run `participant-db-backup` with the same
`PROMOTE_BACKUP`. See [PARTICIPANT_DB.md](PARTICIPANT_DB.md#recovery-and-troubleshooting).

### Cost

One instance per job run, alive only for the duration of the job, plus the long-lived participant
DB container on the Jenkins host. Per-build image tarballs are removed from S3 in `post { always }`.
Every re-merge keeps a noncurrent MERGED version in the versioned bucket; see
[PERMANENT_PIPELINE.md](PERMANENT_PIPELINE.md#merged-file-lifecycle-s3-versions) for a lifecycle rule.

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
