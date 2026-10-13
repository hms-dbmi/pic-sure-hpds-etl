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
| `generate-allconcepts-manifest`               | `etl-runners/generate-allconcepts-manifest/Jenkinsfile`     |
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
  (DB credentials from                        decode participant DB credentials
   the Jenkins server's                         → DB_URL / DB_USERNAME / DB_PASSWORD
   saved file)                                  (skipped for DB-free jobs)
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

- **One principal.** Runners, the participant DB, and the Jenkins agent all run as
  `bdc-etl-jenkins-role` in account 515157839325, which also owns the data. No cross-account role
  is assumed. The only remaining assume is `generate-identity-consent-mapping`'s in-process
  `--role-arn` (the NHLBI exchange role), made by the instance role.
- **No SSH.** Access is AWS SSM Session Manager only; no instance has a key pair.
- **No long-lived ETL host.** The participant DB's credentials exist only while it is up: a
  `600`-mode file on the Jenkins server, written by `participant-db-start` and deleted by
  `participant-db-stop`. So the start, the stop, and every DB-backed runner job must run on the
  same Jenkins node (see [Credential Handling](#credential-handling)).
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
(Terraform found no saved credentials on the Jenkins server). `common/require-db.sh` normally
catches this on the agent first, in seconds, before anything is provisioned.

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
| AWS CLI v2             | image upload, report sync, SSM, EC2 describe                             |
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

### 1. Participant DB Credentials

No AWS secret is needed, and none is created. The DB instance generates its password at boot and
writes its connection details to a root-only file on itself once Postgres has restored.
`participant-db-start` copies that file over SSM to a `600`-mode file on the Jenkins server, and
`participant-db-stop` deletes it. Details in [PARTICIPANT_DB.md](PARTICIPANT_DB.md#credentials).

### 2. IAM

One role, `bdc-etl-jenkins-role`, does everything: the Jenkins server runs as it, and every runner
and the participant DB attach it through the pre-existing instance profile
`bdc-etl-jenkins-temp-profile` (`iam_instance_profile_name` in `environments/<ENV>.tfvars`). The
code creates no IAM resources, security groups, or secrets at runtime. These are the **minimum**
permissions it needs, derived from every AWS call the Jenkinsfiles, scripts, Terraform, and JAR make.

**Used by the Jenkins server** (Terraform, monitoring, DB backup and credentials):

| IAM console service | Actions | Resource | Why |
|---|---|---|---|
| EC2 | `ec2:Describe*` | `*` | Terraform's lookups and refreshes (AMI, subnet, VPC, instance, volumes, ENIs, instance types, attributes); `require-db.sh`, `wait-ready.sh`, `monitor-runner.sh`, the start job's reuse check |
| EC2 | `ec2:RunInstances` | `*` | Launching the DB and every runner |
| EC2 | `ec2:CreateTags` | `*`, condition `ec2:CreateAction = RunInstances` | Tags applied at launch |
| EC2 | `ec2:TerminateInstances` | instances tagged `ManagedBy=terraform` | `terraform destroy` of the DB and runners |
| EC2 | `ec2:ModifyInstanceAttribute` | instances tagged `ManagedBy=terraform` | The AWS provider re-applies `instance_initiated_shutdown_behavior` right after every launch that sets it (the runners' `terminate`), even though `RunInstances` already set it |
| IAM | `iam:PassRole` | `bdc-etl-jenkins-role` | Launching instances with `bdc-etl-jenkins-temp-profile` |
| Systems Manager | `ssm:SendCommand` | the instances **and** `document/AWS-RunShellScript` (both required) | DB backup, fetching the DB credentials, runner log tailing |
| Systems Manager | `ssm:GetCommandInvocation` | `*` (not resource-scopable) | Reading those commands' results |
| S3 | see below | `bdc-etl-data-d0d6191` | Terraform state (`tf_backend/`), image upload, reports, logs, sentinels |

**Used by the instances** (DB and runners):

| IAM console service | Actions | Resource | Why |
|---|---|---|---|
| S3 | `s3:ListBucket`, `s3:ListBucketVersions`, `s3:GetBucketVersioning` | `bdc-etl-data-d0d6191` | Listings and `head-bucket`; the merge and manifest jobs' version listings; their, the DB backup's (and generator pre-flight's) versioning check |
| S3 | `s3:GetObject`, `s3:GetObjectVersion`, `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts` | `bdc-etl-data-d0d6191/*` | Images, dumps and restores, reports, logs, job inputs and outputs, stale-file removal, large multipart uploads. `GetObjectVersion`: the merge reads (and the merge and manifest head) specific object versions |
| Managed policy | `AmazonSSMManagedInstanceCore` (attach it; it is not in the service list) | | Registers the instances with SSM, without which `SendCommand` has nothing to deliver to |
| STS | `sts:AssumeRole` | the NHLBI exchange roles | `generate-identity-consent-mapping` only; those roles must also trust `bdc-etl-jenkins-role` |

S3 versioning must be **Enabled** on `bdc-etl-data-d0d6191`: `participant-db-stop` refuses to
overwrite the restore point otherwise. If the root volumes are encrypted with a customer-managed KMS
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
- **Optional, for manual recovery only:** `ssm:StartSession` (connect to a database kept for
  debugging), `ec2:StartInstances` (restart a stopped DB), `s3:GetObjectVersion` (restore an older
  dump by hand).
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
enforcing). The participant DB installs Postgres from the RHEL9 `postgresql:<pg_version>` module
stream and adds an nftables rule for 5432, since the golden image's firewall drops unlisted
inbound ports.

The subnet must have an S3 path (gateway endpoint or NAT) and reach SSM. No security group is
created at runtime: the participant DB attaches the runners' shared group, which must carry a
standing inbound rule for TCP 5432 **from itself** (set up by hand on `sg-0932143f21f7c533b`). That
lets the runners reach Postgres, and so can anything else in that group.

### Credential Handling

The DB password is generated on the DB instance at boot, and is new for each instance.
`participant-db-start` reads it over SSM Run Command and saves it, mode `600` (directory `700`), at
`${HPDS_ETL_CREDENTIALS_DIR:-$HOME/.hpds-etl}/participant-db-<ENV>.json` on the Jenkins server;
`participant-db-stop` deletes that file. Because it is a local file, `participant-db-start`,
`participant-db-stop`, and every DB-backed runner job must run on the same Jenkins node (all are
`agent any`, which is fine on a single-node Jenkins).

The DB-backed runners' `terraform plan`/`apply` read the file into `TF_VAR_db_credentials_json`
inside the recipe shell, and the runner decodes it from its user data and writes the values to a
`600`-mode file passed as `podman --env-file` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), keeping
them out of the process table and `podman inspect`. `xtrace` is disabled in both bootstraps.

The password is never printed to the Jenkins console, but it is **not** confined to Jenkins: it
passes through SSM command output (kept in SSM's command history), and it is stored in each
DB-backed runner's EC2 user data and Terraform state, including older versions of that state in
the versioned `tf_backend` bucket. It stays valid only for the life of that one DB instance.

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
| `generate-allconcepts-manifest`                 | `true`  | `ETL_JOB_GENERATE_ALLCONCEPTS_MANIFEST_ENABLED`                 |
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

There is **one participant database per environment**. A `participant-db-start` that finds one
already running reuses it (or, with `RESTART_DB_IF_RUNNING=true`, dumps and replaces it), so
**do not run two pipelines at once in the same environment**: the second would share or replace
the first's database, and whichever finishes first stops it under the other. See
[PARTICIPANT_DB.md](PARTICIPANT_DB.md#when-a-database-is-already-running).

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
│  └─ development.tfvars        account, VPC, security group, instance role
├─ common/
│  ├─ lib.sh                    check/soft/fail/warn/summary assertion helpers
│  ├─ monitor-runner.sh         polls for the sentinel; exits with the job's exit code
│  ├─ db-credentials-path.sh    where the participant DB credentials are saved on Jenkins
│  ├─ require-db.sh             fails fast on the agent when the participant DB is down
│  └─ validate-report.sh        assertions true of every JobResult report
├─ participant-db/              SHARED: the per-pipeline Postgres server
│  ├─ Jenkinsfile.start  Jenkinsfile.stop  Makefile  wait-ready.sh  backup-via-ssm.sh
│  ├─ fetch-credentials.sh      copies the DB credentials from the instance to Jenkins (SSM)
│  └─ terraform/                instance, user_data.sh.tpl, backup.sh.tpl
├─ participants-migration/            TEMPORARY
├─ split-allconcepts/                 TEMPORARY
├─ sstr-populate-rds-participants/    PERMANENT
├─ generate-global-all-concepts/      PERMANENT
├─ create-vcf-indexes/                PERMANENT
├─ merge-allconcepts/                 PERMANENT (no DB)
├─ generate-allconcepts-manifest/     PERMANENT (no DB), last stage of both orchestrators
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

### Participant DB Left Running

If an orchestrator ends with "participant-db-stop … ended FAILURE", the instance is still up and
may hold writes no dump has captured. A failed build with `KEEP_DB_ON_FAILURE=true` (the default)
leaves it up on purpose, for debugging. Re-run `participant-db-stop`; it backs up before
destroying. The next pipeline reuses a database left running unless `RESTART_DB_IF_RUNNING=true`. See
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
