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
| `qa-pipeline-counts`                          | `etl-runners/qa-pipeline-counts/Jenkinsfile`                |
| `all-concepts-data-generator`                 | `etl-runners/all-concepts-data-generator/Jenkinsfile`       |
| `generate-identity-consent-mapping`           | `etl-runners/generate-identity-consent-mapping/Jenkinsfile` |

`all-concepts-data-generator` is triggered per unprocessed study by the permanent
orchestrator's `Generate per-study AllConcepts` stage, and can also be run by hand.
`generate-identity-consent-mapping` is standalone — no orchestrator triggers it; run it when a
new DMC harmonization drop lands (ALS-12727).
`qa-consent-counts` is standalone too: run it to check the participant DB's consent counts against
dbGaP (see [below](#qa-consent-counts-standalone)). `qa-pipeline-counts` is the last step of both
orchestrators (see [below](#qa-pipeline-counts-end-of-both-pipelines)), and can also be run by hand.

### `qa-pipeline-counts` (end of both pipelines)

**Job:** `qa-pipeline-counts` ([`etl-runners/qa-pipeline-counts/`](../etl-runners/qa-pipeline-counts/))
**Class:** [`QaPipelineCountsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/qa/QaPipelineCountsJob.java)
**Runs:** last in both orchestrators' `post { always }`, after the final report and the participant
DB backup, when the run succeeded (or is UNSTABLE) and was not a pre-flight; also on its own.
`QA_PIPELINE_COUNTS_JOB` (blank skips it) names the job in both orchestrators.

For **every study marked ready** in `MANAGED_INPUTS` -- the original file, so excluded studies and
those the run did not process are checked too, as a regression test -- it compares each consent
group's counts with that study version's dbGaP summary:

| Measure | Covers | Compared with |
|---|---|---|
| `db:participants` | every study | `subject_count` |
| `db:samples` (the study's own `DBGap` samples) | every study | `sample_count` |
| `participants-migration`, `split-allconcepts`, `migration-report:loaded`, `migration-report:split` | migration: the studies the run processed | `subject_count` |
| `sstr-load`, `all-concepts-data-generator`, `pipeline-report:loaded`, `pipeline-report:written` | permanent: the studies the run processed | `subject_count` |

The run's counts are collected from `downstream-artifacts/` by
[`collect-run-counts.py`](../etl-runners/qa-pipeline-counts/collect-run-counts.py) (failed runs and
blank counts skipped) and passed as `RUN_COUNTS`; the DB counts, the run counts and the cached
summaries are read in parallel.

**Summaries.** Read from `{DATA_ROOT}/{study}/rawData/{study}_{version}_summary.json` (e.g.
`.../phs004477/rawData/phs004477_v1_summary.json`; the file holds the summary's `study` object; the
API's whole response is read too). Without that file, a `{study}*study_summary.json` in the same folder
is read instead (the one for the study's version). A study whose summary is not there (or unreadable) depends on
`FETCH_MISSING_SUMMARIES`, which the orchestrators set from their own `QA_FETCH_MISSING_SUMMARIES`
(**unchecked by default in the permanent pipeline, checked in the migration pipeline**):

- **off:** dbGaP is not asked. The study fails QA: `SUMMARY_MISSING`, a warning like any other finding.
- **on:** the summary is asked of the dbGaP SSTR API, and each answer is written there at once
  (`CACHE_SUMMARIES`), so each study version is fetched once, ever.

When fetching, dbGaP is volatile, so it is asked gently: one study at a time, 2 s apart (the
gap doubling, up to 60 s, while it answers overloaded), 3 attempts per study (60 s then 120 s
apart, `Retry-After` honoured), 2 minutes' pause after it cannot be connected to, no more lookups
after 20 studies in a row get no answer, and none started after **6 hours**
(`DBGAP_DEADLINE_SECONDS`). A study still without a summary is `UNVERIFIED` (a
`DBGAP_UNAVAILABLE` warning): the build is UNSTABLE and a later run fetches it. An answer for
another study version than requested is `VERSION_MISMATCH` and is not cached.

**Findings** (statuses; each is a WARNING per study and kind, and makes the build UNSTABLE):
`COUNT_MISMATCH`, `MISSING` (dbGaP lists the group; the source has none of it), `NOT_IN_DBGAP`,
`DBGAP_COUNT_MISSING`, `NO_VERSION`, `VERSION_NOT_FOUND`, `VERSION_MISMATCH`, `SUMMARY_MISSING`,
`DBGAP_UNAVAILABLE`.
`NOT_LOADED_BY_DESIGN` -- dbGaP's consent 0 (not consented) with no participants loaded -- and
`MATCH` are not findings. The orchestrator also only marks itself UNSTABLE, never FAILED, when QA
cannot run: the ETL already succeeded. Exit 4 only when fetching and no study has a summary at all
(none cached, dbGaP answering for none); `RETRY_ON_INFRA` reruns it once. With fetching off, studies
without a summary file each fail QA instead, and the run is not retried.

**Outputs** (archived on the QA build, copied to the orchestrator's
`downstream-artifacts/qa-pipeline-counts/`, and uploaded beside the run's report under
`s3://<DATA_ROOT bucket>/etl-runner/reports/<permanent|migration>-pipeline/<build-tag>/qa-pipeline-counts/`):

| File | What it is |
|---|---|
| `qa-pipeline-report.md` | to read: the result and totals, then, per study with discrepancies, a table of its consent groups (rows) by dbGaP's counts and every measure (columns), differing counts in bold with dbGaP's beside them; then version problems, studies not checked, and every study |
| `qa_pipeline_counts.csv` | one row per study, consent group and measure: `study_id, version, in_run, consent_code, consent_short_name, measure, compared_to, dbgap_count, count, status, note` |
| `qa_pipeline_discrepancies.csv` | its rows needing follow-up (every status but `MATCH` and `NOT_LOADED_BY_DESIGN`) |
| `qa_pipeline_studies.csv` | one row per study: `study_id, abv, version, in_run, summary_source, status, consent_groups, discrepancies, note` |
| `qa-pipeline-counts-<run-id>.json` | the report: `fetchMissingSummaries`, `studiesChecked`, `studiesInRun`, `studiesVerified`, `countsCompared`, `countsMatching`, `discrepancies`, `discrepanciesByType`, `discrepanciesByMeasure`, `studiesWithDiscrepancies`, `unverifiedStudies`, `summariesFromCache`, `summariesFromApi`, `summariesCached`, `summaryCacheFailures`, `unusableCachedSummaries`, `apiLookups`, `apiAttempts`, `apiRetries`, `runCounts`, `runCountSources` |

**Run it on its own** (say, after a failed run is re-run, or to re-check every study): give
`MANAGED_INPUTS` and `DATA_ROOT`, and optionally `RUN_COUNTS` (blank: every study DB vs dbGaP
only). The participant DB must be up (`participant-db-start`). Its runner needs HTTPS egress to
`www.ncbi.nlm.nih.gov` (see [network egress](#qa-consent-counts-network-egress)) and write access
under `DATA_ROOT` only when `FETCH_MISSING_SUMMARIES` is checked (it is unchecked by default), to
fetch and cache summaries not yet there.

### `qa-consent-counts` (standalone)

**Job:** `qa-consent-counts` ([`etl-runners/qa-consent-counts/`](../etl-runners/qa-consent-counts/))
**Class:** [`QaConsentCountsJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/qa/QaConsentCountsJob.java)
**Runs:** by hand only; neither orchestrator triggers it. Reads the participant DB (which must be
up: run `participant-db-start` first) and writes nothing to it.

For each study to check, counts the distinct participants per consent group in the participant
DB's `consents` table and compares them with dbGaP's `subject_count` for that consent group of the
study **version**, read from the dbGaP SSTR API:
`GET https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study/{phs}.{v}/summary`
(`study.consent_groups[]`; one request per study, its answer covering every consent group). With
`LOAD_COUNTS` it also checks the DB against what a run's loads reported. **The DB is
authoritative**: a load report that disagrees with it is a discrepancy too.

Give exactly one of (pre-flight fails on neither or both):

- `MANAGED_INPUTS`: a managed inputs CSV (`s3://`); its studies marked ready are checked, by
  `Study Identifier` and `Version` (e.g. `v4`). `STUDY_FILTER` narrows them to some study ids.
- `STUDIES`: the study versions to check, e.g. `phs000956.v6, phs001412.v4`, separated by commas,
  spaces or new lines; a `.pN` suffix is ignored. The managed inputs are not read, and
  `STUDY_FILTER` must be blank. A malformed entry (`BAD_STUDY`), a study listed with two versions
  (`STUDY_LISTED_TWICE`) or a `STUDY_FILTER` given too (`STUDIES_WITH_FILTER`) fails validation.

`LOAD_COUNTS` is optional: a CSV `study_id,consent_code,participants_loaded[,source]`, other
columns ignored, so a migration run's `migration_consent_counts.csv` or
`participants_migration_consent_counts.csv` works as is. Studies it does not list, and groups with
a blank `participants_loaded` (not loaded that run), are compared DB vs dbGaP only. The MULTI
fallback drops consent 0 subjects, so for a study loaded from MULTI a consent 0 that dbGaP lists is
still a discrepancy (`MISSING_IN_DB`), noted "source MULTI drops consent 0".

Consent codes are matched after normalizing (`c1`, `01` and `1` are one group). A group on one
side only counts as 0 on the other. Consent 0 is compared like any other code.

| Status | Meaning |
|--------|---------|
| `MATCH` | dbGaP, the DB (and the load report, when it covers the study) agree |
| `COUNT_MISMATCH` | dbGaP and the DB both have the group, with different counts |
| `MISSING_IN_DB` | dbGaP lists subjects in a group the DB has none in (DB 0) |
| `MISSING_IN_DBGAP` | the DB has participants in a group dbGaP does not list (dbGaP 0) |
| `DBGAP_COUNT_MISSING` | dbGaP lists the group without a `subject_count` |
| `REPORT_DB_MISMATCH` | the load report disagrees with the DB |
| `NO_VERSION` | the study has no usable `Version` in managed inputs; not looked up |
| `VERSION_NOT_FOUND` | dbGaP has no such study version (HTTP 400, `error_code` 2); never retried |
| `VERSION_MISMATCH` | dbGaP answered for a different accession or version than requested; its counts are not compared |
| `UNVERIFIED` | dbGaP gave no usable answer after the retries (see below); a warning, not a discrepancy |

A row can carry two findings (`COUNT_MISMATCH;REPORT_DB_MISMATCH`). `study_status` is not checked.

**Pacing.** dbGaP's API is slow (~13 s a call) and its gateway answers HTTP 502 when sent several
requests at once, so studies are looked up one at a time (`CONCURRENCY` 1) and requests are at
least `DBGAP_REQUEST_SPACING_SECONDS` (2 s) apart, counted from both the previous request's start
and its end. While dbGaP answers overloaded (429, 502, 503, 504 or a request timeout) the gap
doubles, up to 60 s; each answered request halves it again, back to the setting. ~600 studies take
~2.5 h when dbGaP is healthy.

**Outcome.** Every discrepancy is a WARNING issue, so the job succeeds with warnings, `validate.sh`
exits 10, and the build is **UNSTABLE**. A request is retried, gently, on 429, 408, 5xx, timeouts,
connection errors and a 200 whose body is not the expected JSON: up to 3 requests in all (2
retries), waiting 60 s then 120 s (a `Retry-After` header, seconds or HTTP-date, is honoured
instead, up to 5 minutes), 60 s per request. Any other 4xx is not retried. A study that failed
because dbGaP could not be **connected to** at all (connection refused or reset, DNS or TLS
failure, connect timeout) holds back every lookup not yet started for 2 minutes
(`DBGAP_UNREACHABLE_PAUSE_SECONDS`); a slow or error answer does not. So that an outage ends with
its report rather than in the runner's monitor timeout (exit 124, nothing written), no lookup
starts once 20 studies in a row got no usable answer, or 3.5 h into the lookups; those studies are
`UNVERIFIED` too. A study still unanswered is `UNVERIFIED`, one `DBGAP_UNAVAILABLE` WARNING each:
the job **succeeds with warnings** (UNSTABLE), every other study is compared, and both CSVs and the
JSON report are written and archived as usual. Only when dbGaP answered for **no** study does the
job fail with exit 4 (retryable; `RETRY_ON_INFRA` reruns it once), the CSVs and the JSON report
(metrics and issues included) still written. A `VERSION_NOT_FOUND` answer counts as an answer.

**Outputs** (archived on the build, and under
`s3://bdc-etl-data-d0d6191/etl-runner/reports/qa-consent-counts/<run-id>/`):
`qa_consent_counts.csv` (one row per study and consent code) and
`qa_consent_count_discrepancies.csv` (its non-`MATCH` rows), columns `study_id, version,
consent_code, consent_short_name, dbgap_subjects, db_participants, load_participants, load_source,
status, note`; and the JSON report's metrics `studiesChecked`, `studiesVerified`,
`consentsCompared`, `consentsMatching`, `discrepancies`, `discrepanciesByType`,
`studiesWithDiscrepancies`, `studiesAnswered`, `unverifiedStudies`, `apiAttempts`, `apiRetries`.

**Network.** The runner needs HTTPS egress to `www.ncbi.nlm.nih.gov`; see
[QA consent counts: network egress](#qa-consent-counts-network-egress).

Each parameter maps to the job's `--` flag (blank Terraform values fall back to the job's own
defaults, which match these):

| Parameter                       | Default                                               | Description |
|---------------------------------|-------------------------------------------------------|-------------|
| `MANAGED_INPUTS`                | (blank)                                               | `--managed-inputs`: CSV (`s3://`) whose ready studies are checked, by `Study Identifier` and `Version`. This or `STUDIES`, not both |
| `STUDIES`                       | (blank)                                               | `--studies`: study versions to check instead, e.g. `phs000956.v6, phs001412.v4` (commas, spaces or new lines; `.pN` ignored). This or `MANAGED_INPUTS`, not both |
| `LOAD_COUNTS`                   | (blank)                                               | `--load-counts`: CSV `study_id,consent_code,participants_loaded[,source]` of a run's loads (other columns ignored; a blank `participants_loaded` means not loaded that run, so not compared), e.g. a migration run's `migration_consent_counts.csv`; blank skips the load-report check |
| `STUDY_FILTER`                  | (blank)                                               | `--study-filter`: with `MANAGED_INPUTS` only, comma-separated study ids; blank checks every ready study |
| `CONCURRENCY`                   | `1`                                                   | Studies looked up in dbGaP at once, 1-16. Keep it at 1: each call takes ~13 s and dbGaP's gateway answers 502 when sent several at once |
| `DBGAP_REQUEST_SPACING_SECONDS` | `2`                                                   | Least gap between requests, after the previous one starts and ends, 0-600 (0: off); doubles up to 60 s while dbGaP answers overloaded (429, 502, 503, 504, a timeout), narrowing back as it answers |
| `DBGAP_BASE_URL`                | `https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study`  | `{phs}.{v}/summary` is appended |
| `DBGAP_MAX_ATTEMPTS`            | `3`                                                   | Requests per study, retries included, 1-20 (one study, one request: its answer covers every consent group) |
| `DBGAP_INITIAL_BACKOFF_SECONDS` | `60`                                                  | Wait after the first failure, doubling per retry, 0-600 |
| `DBGAP_MAX_BACKOFF_SECONDS`     | `120`                                                 | Longest wait, 1-3600; a `Retry-After` header is honoured instead (up to 5 minutes) |
| `DBGAP_TIMEOUT_SECONDS`         | `60`                                                  | Per-request timeout, 1-600 |
| `DBGAP_GIVE_UP_AFTER`           | `20`                                                  | Stop asking dbGaP once this many studies in a row got no usable answer, 1-1000; the rest are `UNVERIFIED`, so an outage ends with the report rather than retrying every study |
| `DBGAP_UNREACHABLE_PAUSE_SECONDS` | `120`                                               | After a study fails because dbGaP could not be connected to (connection error or connect timeout, after its retries), no new lookup starts for this long, 0-3600 (0: off); lookups already running carry on |
| `DBGAP_DEADLINE_SECONDS`        | `12600`                                               | No lookup starts after this many seconds, 1-86400; keep it well inside `JOB_TIMEOUT_SECONDS`, or a slow dbGaP ends in a monitor timeout (exit 124) with no report |
| `INSTANCE_TYPE`                 | `t3.medium`                                           | Runner instance type |
| `JOB_TIMEOUT_SECONDS`           | `18000`                                               | Monitor timeout (exit 124); the job's own Jenkins timeout is 11 h, for two attempts |
| `RUN_ID`, `PREBUILT_IMAGE_TAR`, `SKIP_TESTS`, `RETRY_ON_INFRA`, `ENV` | | As for every runner job; `RETRY_ON_INFRA` reruns it once after exit 4 (dbGaP answered for no study) |

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
  post { always }: build job: participant-db-backup    PROMOTE_BACKUP = build SUCCESS/UNSTABLE (DB stays up)
                   pipeline-report / migration-report  downstream-artifacts/<report>/ + S3
```

Build and test run once in the orchestrator as the gate for the whole run. Downstream jobs are
invoked with `SKIP_TESTS=true` so the same commit's suites are not re-run per study. Runner
jobs go further: each orchestrator builds the runner image once and passes it as
`PREBUILT_IMAGE_TAR`, so those builds (SSTR load, allConcepts generation, large splits) build
nothing on the Jenkins host; smaller splits run on a runner pool with no per-study build at all.
The permanent orchestrator passes it to every runner job it calls (SSTR load, global AllConcepts,
VCF indexes, per-study allConcepts, merge and manifest).

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
| `python3`              | the migration pre-flight parses the study-list CSV with its `csv` module; the end-of-run reports, and the QA's validator |
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

`qa-consent-counts`, and `qa-pipeline-counts` for summaries not yet cached, call the dbGaP SSTR API
from their runner, so **the runner security group
(`sg-0932143f21f7c533b`) and the subnet's route must allow outbound HTTPS (TCP 443) to
`www.ncbi.nlm.nih.gov`** (a public endpoint: it needs a NAT or other internet route; an S3 gateway endpoint does not reach it), besides the
participant DB and S3. Without it every lookup times out, each study's rows are `UNVERIFIED`, and
the job exits 4 (dbGaP answered for no study). Studies dbGaP fails to answer for while answering
others are `UNVERIFIED` warnings: the build is UNSTABLE and the reports are published. Its pre-flight probes the API from the Jenkins agent, which
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
`sstr_{study_id}.{v}.txt` (case-insensitive; `{study_id}*telemetry_report.tsv` and
`BDC-ingestion-only__sstr_*` also accepted) — the
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
`all-concepts-data-generator`, `qa-consent-counts`, `qa-pipeline-counts`) run on its own needs the
participant DB up: run `participant-db-start` first (it leaves the database up; run
`participant-db-backup` afterwards to save the runner's writes; the QA jobs write nothing to it). Without it, `require-db.sh`
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
| `qa-pipeline-counts`                            | `true`  | `ETL_JOB_QA_PIPELINE_COUNTS_ENABLED`                            |
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
├─ qa-consent-counts/                 PERMANENT QA, standalone (reads the DB, calls dbGaP; + test/)
├─ qa-pipeline-counts/                PERMANENT QA, last in both orchestrators (reads the DB, caches dbGaP
│                                     summaries under DATA_ROOT; collect-run-counts.py; + test/)
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
