# Jenkins Pipelines and Ephemeral ETL Runners

This document describes how the hpds-etl jobs are orchestrated: two Jenkins pipelines, one
ephemeral EC2 runner per job, provisioned with Terraform and torn down after each run. Jobs are
selected at runtime from a single fat JAR and communicate outcome through process exit codes.

The infrastructure pattern matches
[`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — the same
`terraform-modules/etl-runner` shape, `jenkins-s3-role`, `s3://<stack>/etl-runner/{container,logs}/`
layout, and per-pipeline `terraform/` directory driven by a `Makefile` — with Java in place of
Python.

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

| Pipeline                | File                                                 | Scope                         | Lifetime                                                    |
|-------------------------|------------------------------------------------------|-------------------------------|-------------------------------------------------------------|
| **Temporary migration** | [`/Jenkinsfile`](../Jenkinsfile)                     | only `JobType.MIGRATION` jobs | deleted once the migration has run everywhere               |
| **Permanent ETL**       | [`/Jenkinsfile.permanent`](../Jenkinsfile.permanent) | only `JobType.PERMANENT` jobs | ongoing; rename to `Jenkinsfile` when the migration is gone |

The two are separate files because their lifecycles are opposites. A migration is a one-off that
ends in deletion; permanent ingestion runs indefinitely. Keeping them apart means the permanent
pipeline's schedule, retention, and alerting are not entangled with work whose endpoint is
`git rm`, and retiring the migration is a file deletion rather than an edit to the pipeline that
runs every week.

## Jenkins Jobs

Eight jobs, all pointing at this repository:

| Jenkins job                               | Script path                                              |
|-------------------------------------------|----------------------------------------------------------|
| `new-hpds-etl-participant-migration-pipeline` | `Jenkinsfile.migration`                     |
| `participants-migration`         | `etl-runners/participants-migration/Jenkinsfile`         |
| `split-allconcepts`              | `etl-runners/split-allconcepts/Jenkinsfile`              |
| `hpds-etl-pipeline`              | `Jenkinsfile`                                            |
| `sstr-populate-rds-participants` | `etl-runners/sstr-populate-rds-participants/Jenkinsfile` |
| `generate-global-all-concepts`   | `etl-runners/generate-global-all-concepts/Jenkinsfile`   |
| `create-vcf-indexes`             | `etl-runners/create-vcf-indexes/Jenkinsfile`             |
| `generate-identity-consent-mapping` | `etl-runners/generate-identity-consent-mapping/Jenkinsfile` |

`generate-identity-consent-mapping` is standalone — no orchestrator triggers it; run it
when a new DMC harmonization drop lands (ALS-12727).

The orchestrators' job-name parameters (`PARTICIPANTS_MIGRATION_JOB`, `SSTR_JOB`,
`ALL_CONCEPTS_JOB`, etc.) default to these names. If your naming differs, change the
parameter rather than the pipeline.

---

## Architecture

### Orchestration

The DAG lives in Jenkins, not in the JAR. Each orchestrator stage triggers that job's own
pipeline, which owns its runner end to end.

```
/Jenkinsfile  (or /Jenkinsfile.permanent)
  Build ▸ Tests                              gate: nothing is provisioned until these pass
  └─ stage 'Migrate participants'
       └─ build job: participants-migration  etl-runners/<job>/Jenkinsfile
            Init ▸ Build JAR ▸ Pre-flight ▸ Package image
            ▸ Provision (terraform apply) ▸ Monitor ▸ Fetch reports ▸ Validate
            post: terraform destroy, archive reports
```

Build and test run once in the orchestrator as the gate for the whole run. Downstream jobs are
invoked with `SKIP_TESTS=true` so the same commit's suites are not re-run per study.

### Runner Lifecycle

```
Jenkins agent                          ephemeral EC2 (self-terminating)
─────────────                          ────────────────────────────────
./mvnw package        ─ target/hpds-etl.jar
docker build          ─ hpds-etl-runner image
docker save | gzip    ─▶ s3://<stack>/etl-runner/container/<run>.tar.gz
terraform apply       ─▶ launch instance ──▶ user_data:
                                              fetch RDS creds (Secrets Manager, instance role)
                                              docker load + docker run
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

Properties of this model:

- **No SSH.** Access is AWS SSM Session Manager only; the instance has no key pair.
- **No long-lived ETL host** and no Jenkins agent holding database credentials.
- **Self-terminating.** The instance terminates whether the job succeeded, failed, was
  OOM-killed, or had its spot capacity reclaimed.
- **Completion is a sentinel, not a log match.** `status.json` carries the job's exit code and is
  uploaded last, so its presence also proves every other artifact reached S3. The BDC Python
  pipelines detect completion by grepping the log for phrases such as `All studies processed`;
  these jobs already exit with a precise code, so there is nothing to pattern-match.

---

## Exit Codes

`ExitCode.java` is the interface between a job and Jenkins.

| Code | Name                                | Pipeline behaviour                                                                   |
|-----:|-------------------------------------|--------------------------------------------------------------------------------------|
|    0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | continue; the report distinguishes the two, and warnings mark the build UNSTABLE     |
|    1 | `UNKNOWN`                           | fail                                                                                 |
|    2 | `VALIDATION_FAILED`                 | fail. For the migration this also means *some studies failed while others succeeded* |
|    3 | `DATA_ERROR`                        | fail. For SSTR the study was rolled back, so RDS is unchanged                        |
|    4 | `INFRASTRUCTURE_ERROR`              | **retried once**, then fail                                                          |
|    5 | `CONFIG_ERROR`                      | fail; no retry — a retry cannot fix a missing parameter                              |
|  124 | (monitor)                           | timed out waiting for the runner; no retry                                           |

---

## Validation

Four layers, each catching what the others cannot.

### 1. Test Suites

`./mvnw verify` runs the unit suites plus the Testcontainers `*IT` suites against real Postgres
and LocalStack. These are the only checks that assert real database state; every later layer
reasons about report metrics. Run by the orchestrators, skipped in the job pipelines.

### 2. Pre-flight Checks

`etl-runners/<job>/preflight.sh` runs on the agent before any instance is provisioned, checking
what is knowable from the input files alone.

| Runner                           | Checks                                                                                                                                                                                                                           |
|----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `participants-migration`         | study-list CSV parses and has its three required columns; at least one study is ready; `consents.csv` exists; every ready study has its `{ABV}_PatientMapping.v2.csv`. Also reports which studies take the sstr vs direct route. |
| `sstr-populate-rds-participants` | `--study-id` matches `phs######`; input exists and is non-empty; header is tab-delimited and carries `dbgap_subject_id`, `CONSENT`, `consent_abbreviation`, `dbgap_sample_id`. Reads only the first 64 KiB via a ranged GET.     |

`consents.csv` is required unconditionally: `execute()` reads it before processing any study, so
its absence aborts the whole run rather than one study.

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

Optional per-study expectations from `studies.tsv` (`expected_consent_codes`,
`expected_min_participants`) catch a file swapped for the wrong study and a truncated input — the
two failures no invariant can detect, because a truncated file is internally consistent.

**`participants-migration`** — checks artifacts, not just the exit code, because the job records a
per-study data problem as a study-level failure and continues, and skips unmatched ids with only a
log warning. Neither is visible in the exit status.

- `readyStudies > 0`, `failedStudies == 0`, `succeeded + failed == ready`
- one `STUDY_MIGRATED` record per success
- one `*_hpds_id_mapping.csv` per succeeded study
- per mapping file: exact header, at least one row, well-formed UUIDs, no blank ids, and no
  duplicated `old_hpds_id` (one legacy patient mapped to two new uuids is the corruption this
  migration exists to avoid)
- each sstr sub-report validated; a mapping file with fewer rows than its study's sstr subject
  count is warned about, being the visible symptom of silently dropped patients

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

| Requirement       | Notes                                                                    |
|-------------------|--------------------------------------------------------------------------|
| JDK 25            | matches `<java.version>` in `pom.xml`                                    |
| Maven wrapper     | `./mvnw`, checked into the repo                                          |
| Docker            | builds the runner image                                                  |
| Terraform ≥ 1.3   | provisions the runner                                                    |
| AWS CLI v2        | image upload, report sync, SSM, EC2 describe                             |
| `jq`              | report and sentinel parsing                                              |
| `python3`         | the migration pre-flight parses the study-list CSV with its `csv` module |
| `jenkins-s3-role` | instance profile on the agent                                            |

This is the same agent the BDC ETL runners use, plus JDK 25.

### Jenkins Plugins

| Plugin                | Used for                                                                                           |
|-----------------------|----------------------------------------------------------------------------------------------------|
| Pipeline              | declarative pipelines                                                                              |
| Pipeline: Basic Steps | the `unstable` step                                                                                |
| JUnit                 | surefire and failsafe reports                                                                      |
| Copy Artifact         | pulling a downstream job's reports onto the orchestrator build (optional — guarded by `try/catch`) |

---

## AWS Setup

### 1. RDS Secret

Create a Secrets Manager secret holding the ETL user's credentials, then set `rds_secret_id` in
both `terraform/*.tfvars`. Either shape is accepted:

```json
{ "url": "jdbc:postgresql://host:5432/hpds", "username": "hpds_etl", "password": "…" }
```

```json
{ "host": "host", "port": 5432, "dbname": "hpds", "username": "hpds_etl", "password": "…" }
```

The second is what an RDS-managed secret produces, so a rotated secret works unchanged.

### 2. IAM

`jenkins-s3-role` needs:

- `secretsmanager:GetSecretValue` on the secret
- read on the input buckets
- read/write on the stack bucket's `etl-runner/*` prefixes
- `AmazonSSMManagedInstanceCore`

The full policy is in
[`terraform-modules/etl-runner/README.md`](../terraform-modules/etl-runner/README.md). The role
is shared with the BDC pipelines and managed centrally, which is why `manage_secret_access`
defaults to `false`.

### 3. Networking

`subnet_id` must have a route to the HPDS RDS instance **and** an S3 path (gateway endpoint or
NAT). The values in `*.tfvars` are copied from the BioLINCC runner, which does not talk to RDS,
so this is the one setting that configuration cannot vouch for. Confirm it before the first run.

### Credential Handling

Credentials never reach Jenkins. Terraform receives the secret's id, never its value, so nothing
sensitive enters Terraform state, the console log, or the EC2 user-data blob. On the instance the
values are written to a `600`-mode file passed as `docker --env-file`, keeping them out of the
process table and `docker inspect`. `xtrace` is disabled in the bootstrap for the same reason.

---

## Usage

### Full Migration

Run `new-hpds-etl-participant-migration-pipeline` with `MANAGED_INPUTS` and `DATA_FOLDER`. Set `PREFLIGHT_ONLY` to
validate the export layout without provisioning anything.

### Permanent Sweep

Run `hpds-etl-pipeline` with `STUDY_ID` blank. Every study marked ready in
[`studies.tsv`](../etl-runners/sstr-populate-rds-participants/studies.tsv) is loaded, one
ephemeral runner each, sequentially. `CONTINUE_ON_STUDY_FAILURE` (default on) lets one bad study
fail without stopping the rest; the build ends with a per-study summary table.

### Single-Study Reload

Run `hpds-etl-pipeline`, or the SSTR job directly, with `STUDY_ID` set. The manifest's `ready`
flag is ignored in this mode, so an explicit reload is not blocked by a sweep flag.

A reload is safe: purge and load share one transaction, so a failure leaves RDS exactly as it
was. Expect `participantsInserted = 0` and an UNSTABLE build, which is correct for a reload.

### Adding a Study

Append a row to `studies.tsv`. Populate `expected_consent_codes` and
`expected_min_participants` where known — they are what catch a wrong or truncated file.

### Local Run

```bash
cd etl-runners/sstr-populate-rds-participants

export TF_VAR_run_id=local-1 TF_VAR_study_id=phs001412 \
       TF_VAR_input_uri=s3://…/phs001412.sstr.txt TF_VAR_name_suffix=local1

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
| `participants-migration`                        | `true`  | `ETL_JOB_PARTICIPANTS_MIGRATION_ENABLED`                        |

Running a disabled job exits `5` (`CONFIG_ERROR`) with a message naming the flag.

Notes:

- `participants-migration` also requires `etl.jobs.sstr-populate-rds-participants.enabled`,
  because it injects that job to load the sstr-backed studies. Both flags are on its condition,
  so disabling the sstr job removes the migration job cleanly instead of breaking Spring context
  startup on a missing bean.
- This is the retirement path for the migration: set
  `ETL_JOB_PARTICIPANTS_MIGRATION_ENABLED=false` everywhere, confirm nothing calls it, then
  delete the job, its runner directory, and `/Jenkinsfile`.

---

## Concurrency

Study loads may run in parallel. Study scoping alone does not make them safe: every SSTR load
writes `participants` with `source = "DBGap"`, so two studies containing the same
`dbgap_subject_id` compete for that subject's HPDS uuid.

`ParticipantRepository.resolveOrCreate` is what makes concurrent loads correct:

- It re-reads after inserting and returns the uuid **actually stored**, so a run whose insert lost
  the race cannot write consents and samples against its own discarded uuid.
  `ON CONFLICT DO NOTHING` reports the loser's insert as "0 rows" without revealing the winner,
  and there are no foreign keys from `consents`/`samples` back to `participants`, so nothing else
  would catch it.
- Inserts are issued in sorted `source_id` order, so two runs inserting an overlapping set of new
  subjects cannot deadlock by acquiring them in opposite orders.

`SstrPopulateRdsParticipantsConcurrencyIT` covers all three properties: shared subjects converge
on one uuid, no consent or sample row references a uuid with no participant, and opposing insert
orders do not deadlock.

Sequential remains the default. Loads that share subjects serialize on those rows anyway — the
loser waits for the winner's transaction to commit — so parallelism buys least where studies
overlap most, and sequential keeps the RDS write load predictable and the console log readable.
Swapping the loop in the `Load SSTR participants` stage for a `parallel` map is a small change if
throughput matters.

---

## Directory Layout

```
Jenkinsfile                     migration orchestrator (TEMPORARY)
Jenkinsfile.permanent           permanent orchestrator
terraform-modules/etl-runner/   shared self-terminating-runner module
etl-runners/
├─ Dockerfile                   one image for every job (the JAR selects the job at runtime)
├─ run-job.sh                   container entrypoint: env vars to --flags, java -jar, exit code
├─ common.mk                    shared build/deploy/monitor targets
├─ common/
│  ├─ lib.sh                    check/soft/fail/warn/summary assertion helpers
│  ├─ monitor-runner.sh         polls for the sentinel; exits with the job's exit code
│  └─ validate-report.sh        assertions true of every JobResult report
├─ participants-migration/          TEMPORARY
│  ├─ Jenkinsfile  Makefile  preflight.sh  validate.sh
│  └─ terraform/                    module call, tfvars, backend config
└─ sstr-populate-rds-participants/  PERMANENT
   ├─ Jenkinsfile  Makefile  preflight.sh  validate.sh  studies.tsv
   └─ terraform/
```

---

## Operations

### Artifact Locations

| Artifact            | Location                                                                            |
|---------------------|-------------------------------------------------------------------------------------|
| Console log         | the Jenkins build                                                                   |
| JSON reports        | archived on the build, and `s3://<stack>/etl-runner/reports/<job>/<run-id>/`        |
| Runner log          | `s3://<stack>/etl-runner/logs/<job>-<run-id>.log` (bootstrap plus container output) |
| Completion sentinel | `status.json` under the report prefix                                               |

### Diagnosing a Failure

1. **Exit code** — identifies which of the five categories the failure falls in.
2. **`status.json`** — its `phase` field distinguishes a bootstrap failure (`install`,
   `credentials`, `image`) from the job itself (`job`).
3. **JSON report** — `inputValidation` and `outputValidation` issues name the specific rows and
   columns.

### Timeouts

The monitor gives up at `JOB_TIMEOUT_SECONDS` (default 7200) and exits 124. The instance still
terminates itself. Check the tail of the runner log in S3 for the last phase reached.

### Leaked Instance Profile

`terraform destroy` runs in `post { always }`. If a build is hard-killed, the instance profile
`<job>-etl-instance-profile-<suffix>` can survive; the instance itself always terminates, so no
compute is billed. Delete the profile manually or re-run `make destroy` with the same `STATE_KEY`.

### Cost

One instance per job run, alive only for the duration of the job. Per-build image tarballs are
removed from S3 in `post { always }`.

---

## Known Limitations

- **No post-load assertion against RDS itself.** Everything after the test suites reasons about
  report metrics, which are counts of rows the repositories affected rather than a `SELECT`
  against the loaded table. The fix is a `verify-rds-participants` job in the JAR, run as a second
  container on the same runner, since only the runner can reach RDS.
- **Reports cannot confirm their own study.** Adding `builder.metric("studyId", studyId)` to
  `SstrPopulateRdsParticipantsJob.report` would let `validate.sh` confirm a report belongs to the
  study it was asked about instead of inferring it from the run id.
- **`studies.tsv` ships with example rows only.** A sweep fails with a clear message until it is
  populated.
- **`instance_type` is an estimate per job.** Both jobs hold their input in memory
  (`List<Telemetry>` for SSTR, the mapping and consents joins for the migration), so the ceiling
  is the largest file rather than the average. Set `instance_type` per study in `studies.tsv`
  where a study needs more headroom.
- **Validation issue codes are inconsistently cased.** `consent_code_COUNT` does not follow the
  `SCREAMING_SNAKE_CASE` convention the other codes use. Renaming it means changing the emitted
  code in `SstrPopulateRdsParticipantsJob` and the matching `jq` filter in the SSTR `validate.sh`
  together.

---

## References

- [`terraform-modules/etl-runner`](../terraform-modules/etl-runner/README.md) — the runner module
- [`etl-runners/README.md`](../etl-runners/README.md) — runner directory conventions
- [`docs/ADDING_A_JOB.md`](ADDING_A_JOB.md) — adding a job and its runner
- [`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) — the pattern this follows
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
