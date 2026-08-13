# Jenkins pipelines and ephemeral ETL runners

Two pipelines, deliberately separate, plus one ephemeral runner per job.

| Pipeline | File | Scope | Lifetime |
|---|---|---|---|
| **Temporary migration** | [`/Jenkinsfile`](../Jenkinsfile) | only `JobType.MIGRATION` jobs | deleted once the migration has run everywhere |
| **Permanent ETL** | [`/Jenkinsfile.permanent`](../Jenkinsfile.permanent) | only `JobType.PERMANENT` jobs | ongoing; rename to `Jenkinsfile` when the migration is gone |

They are separate files because their lifecycles are opposites. A migration is a one-off that
ends in deletion; permanent ingestion runs indefinitely on a schedule. Sharing one pipeline
would mean the weekly load's schedule, retention, and alerting were entangled with work whose
endpoint is `git rm`, and removing the migration would be an edit to the pipeline that runs
every week rather than a clean deletion.

## Shape of a run

The DAG lives in Jenkins, not in the JAR. Each orchestrator stage triggers **that job's own
pipeline**, which owns its runner end to end:

```
/Jenkinsfile  (or /Jenkinsfile.permanent)
  Build ▸ Tests                              ← the gate: nothing is provisioned until these pass
  └─ stage 'Migrate participants'
       └─ build job: participants-migration  ← etl-runners/<job>/Jenkinsfile
            Init ▸ Build JAR ▸ Pre-flight ▸ Package image
            ▸ Provision (terraform apply) ▸ Monitor ▸ Fetch reports ▸ Validate
            post: terraform destroy, archive reports
```

Inside a job pipeline:

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
                                              upload status.json  ← sentinel, LAST
                                              shutdown now   (terminate)
monitor-runner.sh     ◀─ polls EC2 state + tails log via SSM
                      ◀─ reads status.json ▸ exits with the JOB's exit code
aws s3 sync reports   ◀─ the JSON report and any CSVs
validate.sh           ─ assertions over the report
terraform destroy     (post: always)
```

Nothing is ever SSH'd into. There is no long-lived ETL host, no Jenkins agent with database
credentials, and no state left behind: the instance terminates itself whether the job
succeeded, failed, was OOM-killed, or had its spot capacity reclaimed.

This mirrors [`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation) —
same shared `terraform-modules/etl-runner` module, same `jenkins-s3-role`, same
`s3://<stack>/etl-runner/{container,logs}/` layout, same per-pipeline `terraform/` directory
driven by a `Makefile` — with Java in place of Python and one substantive improvement, below.

### Exit codes are the contract

`ExitCode.java` is the whole interface between a job and Jenkins:

| Code | Name | Pipeline behaviour |
|---:|---|---|
| 0 | `SUCCESS` / `SUCCESS_WITH_WARNINGS` | continue; the report distinguishes the two, and warnings mark the build UNSTABLE |
| 1 | `UNKNOWN` | fail |
| 2 | `VALIDATION_FAILED` | fail. For the migration this also means *some studies failed while others succeeded* |
| 3 | `DATA_ERROR` | fail. For SSTR the study was rolled back, so RDS is unchanged |
| 4 | `INFRASTRUCTURE_ERROR` | **retried once**, then fail |
| 5 | `CONFIG_ERROR` | fail; no retry — a retry cannot fix a missing parameter |
| 124 | (monitor) | timed out waiting for the runner; no retry |

The BDC Python pipelines detect completion by grepping the log for phrases like
`All studies processed`. These jobs already exit with a precise code, so the runner writes it
to `status.json` and the monitor reads that instead. A run can therefore never be misread as
successful because a phrase happened to appear in a log line — and because `status.json` is
uploaded *last*, its presence also proves every other artifact already reached S3.

## Validation

Four independent layers, because each catches something the others cannot.

**1. Tests, on the agent, before anything is provisioned.** `./mvnw verify` runs the unit
suites plus the Testcontainers `*IT` suites against real Postgres and LocalStack. These are the
only checks that assert real database state; everything downstream reasons about report
metrics. Run by the orchestrators; skipped in the job pipelines (`SKIP_TESTS=true`) so a
single-study reload does not re-test the same commit.

**2. Pre-flight, on the agent, before anything is provisioned.**
`etl-runners/<job>/preflight.sh` checks the input layout — the failures that are knowable from
the files alone, so they cost seconds instead of an instance:

- *participants-migration* — the study-list CSV parses and has its three required columns; at
  least one study is ready; `consents.csv` exists (read before any study is processed, so its
  absence aborts the whole run); every ready study has its `{ABV}_PatientMapping.v2.csv`. It
  also reports which studies will take the sstr route versus direct population.
- *sstr-populate-rds-participants* — `--study-id` matches `phs######`; the input exists and is
  non-empty; its header is tab-delimited and carries `dbgap_subject_id`, `CONSENT`,
  `consent_abbreviation`, `dbgap_sample_id`. Only the first 64 KiB is read (a ranged GET), so
  the check is cheap regardless of file size.

**3. The job's own lifecycle.** `AbstractJob` validates required params, then inputs, then runs,
then asserts post-conditions in `validateOutput`. Any ERROR-level issue fails the run and
lands in the report.

**4. Report assertions, after the run.** `etl-runners/<job>/validate.sh` asserts over the
JSON report, and treats the job's exit code and its output as **independent gates** — a load
that exits 0 but wrote fewer consent rows than it found subjects is still a failed load.

`common/validate-report.sh` covers every job: valid JSON, `status == SUCCESS`, a success exit
code, zero input- and output-validation errors, no `errorMessage`; warnings are surfaced rather
than swallowed. Then per job:

*sstr-populate-rds-participants* — two of the metrics are exact invariants, not heuristics:

- `consentsWritten == distinctParticipants`. One consent row is built per distinct
  `dbgap_subject_id`, and `ConsentRepository` upserts with `ON CONFLICT DO UPDATE`, so every
  row reports 1 affected. Any other number means rows went missing between file and table.
- `sum(countsByConsentGroup) == distinctParticipants`. The same set, grouped by `CONSENT`.

`participantsInserted` is deliberately *not* asserted equal to anything:
`ParticipantRepository` upserts `ON CONFLICT DO NOTHING`, so it counts only new participants
and is legitimately `0` on a reload. That case is a warning, since it is correct for a reload
and wrong for a study's first load. Optional per-study expectations from `studies.tsv`
(`expected_consent_codes`, `expected_min_participants`) catch a file swapped for the wrong
study and a truncated input respectively — the two failures no invariant can see, because a
truncated file is internally consistent.

*participants-migration* — checks the artifacts, not just the exit code, because the job
deliberately treats a per-study data problem as a study-level failure and continues, and skips
individual unmatched ids with only a log warning. Neither is visible in the exit status. So:
`readyStudies > 0`; `failedStudies == 0`; `succeeded + failed == ready`; one `STUDY_MIGRATED`
record per success; one `*_hpds_id_mapping.csv` per succeeded study; and per mapping file the
exact header, at least one row, well-formed UUIDs, no blank ids, and **no duplicated
`old_hpds_id`** — one legacy patient mapped to two new uuids is the precise corruption this
migration exists to avoid. Each sstr sub-report is validated too, and a mapping file with
fewer rows than its study's sstr subject count is warned about, since that is the visible
symptom of silently dropped patients.

`validate.sh` and `preflight.sh` exit **0** clean, **10** clean-with-warnings (→ Jenkins
UNSTABLE), **1** failed. They never abort on the first failure, so one console read shows
everything wrong with a run.

> `make` collapses every recipe failure to exit 2, so it cannot carry either the ETL exit
> code or the `10` warning signal. The Jenkinsfiles invoke `monitor-runner.sh`,
> `preflight.sh`, and `validate.sh` **directly**; the `make monitor` / `make validate`
> targets exist for local runs, where pass/fail is all you need.

## Layout

```
Jenkinsfile                     migration orchestrator (TEMPORARY)
Jenkinsfile.permanent           permanent orchestrator
terraform-modules/etl-runner/   the shared self-terminating-runner module
etl-runners/
├─ Dockerfile                   one image for every job (the JAR picks the job at runtime)
├─ run-job.sh                   container entrypoint: env ▸ --flags ▸ java -jar ▸ exit code
├─ common.mk                    shared build/deploy/monitor targets
├─ common/
│  ├─ lib.sh                    check/soft/fail/warn/summary assertion helpers
│  ├─ monitor-runner.sh         poll for the sentinel; exit with the job's code
│  └─ validate-report.sh        assertions true of every JobResult report
├─ participants-migration/          TEMPORARY
│  ├─ Jenkinsfile  Makefile  preflight.sh  validate.sh
│  └─ terraform/                    module call + tfvars + backend config
└─ sstr-populate-rds-participants/  PERMANENT
   ├─ Jenkinsfile  Makefile  preflight.sh  validate.sh  studies.tsv
   └─ terraform/
```

## Jenkins setup

Four jobs, all pointing at this repository:

| Jenkins job | Script path |
|---|---|
| `hpds-etl/migration` | `Jenkinsfile` |
| `hpds-etl/participants-migration` | `etl-runners/participants-migration/Jenkinsfile` |
| `hpds-etl/permanent` | `Jenkinsfile.permanent` |
| `hpds-etl/sstr-populate-rds-participants` | `etl-runners/sstr-populate-rds-participants/Jenkinsfile` |

The orchestrators' `PARTICIPANTS_MIGRATION_JOB` / `SSTR_JOB` parameters default to those
names — change the parameter, not the pipeline, if your naming differs.

**Agent requirements:** JDK 25, the Maven wrapper, Docker, Terraform ≥ 1.3, AWS CLI v2, `jq`,
`python3` (the migration pre-flight parses the study-list CSV with its `csv` module), and the
`jenkins-s3-role` instance profile. This is the same agent the BDC ETL runners use, plus JDK 25.

**Plugins:** Pipeline, Pipeline: Basic Steps (`unstable`), JUnit, and Copy Artifact (used to
pull a downstream job's reports onto the orchestrator build — guarded by `try/catch`, so a run
still succeeds without it).

## AWS prerequisites

1. **The RDS secret.** Create a Secrets Manager secret holding the ETL user's credentials,
   then set `rds_secret_id` in both `terraform/*.tfvars`. Either shape works:

   ```json
   { "url": "jdbc:postgresql://host:5432/hpds", "username": "hpds_etl", "password": "…" }
   { "host": "host", "port": 5432, "dbname": "hpds", "username": "hpds_etl", "password": "…" }
   ```

   The second is what an RDS-managed secret produces, so rotation works unchanged.

2. **IAM.** `jenkins-s3-role` needs `secretsmanager:GetSecretValue` on that secret, read on
   the input buckets, read/write on the stack bucket's `etl-runner/*` prefixes, and
   `AmazonSSMManagedInstanceCore`. Full policy in
   [`terraform-modules/etl-runner/README.md`](../terraform-modules/etl-runner/README.md).
   That role is shared with the BDC pipelines and managed centrally, which is why
   `manage_secret_access` defaults to `false`.

3. **Networking.** `subnet_id` must have a route to the HPDS RDS instance **and** an S3 path
   (gateway endpoint or NAT). The values in `*.tfvars` are copied from the BioLINCC runner,
   which does not talk to RDS — so this is the one setting its configuration cannot vouch for.
   Confirm it before the first run.

Credentials never reach Jenkins. Terraform is given the secret's *id*, never its value, so
nothing sensitive enters Terraform state, the console log, or the EC2 user-data blob; on the
instance the values go into a `600`-mode `--env-file`, which keeps them out of the process
table and `docker inspect`. `xtrace` is deliberately off in the bootstrap for the same reason.

## Running things

**A full migration:** run `hpds-etl/migration` with `MANAGED_INPUTS` and `DATA_FOLDER`. Set
`PREFLIGHT_ONLY` to validate the export layout without provisioning anything — worth doing as
soon as the data lands.

**The permanent sweep:** run `hpds-etl/permanent` with `STUDY_ID` blank. Every study marked
ready in [`studies.tsv`](../etl-runners/sstr-populate-rds-participants/studies.tsv) is loaded,
one ephemeral runner each, sequentially. A study's own work is isolated — the consents purge is
scoped to one `study_id`, and each downstream build gets its own Terraform state key and AWS
resource-name suffix — so `CONTINUE_ON_STUDY_FAILURE` (default on) lets one bad study fail
without stopping the rest, and the build ends with a per-study summary table.

### Why parallel study loads are safe

Studies are *not* fully independent, and the reason matters. Every SSTR load writes
`participants` with `source = "DBGap"`, so two studies containing the same
`dbgap_subject_id` are competing for that one subject's HPDS uuid — study scoping does
nothing for them.

`ParticipantRepository.resolveOrCreate` is what makes it correct: it re-reads after inserting
and returns the uuid **actually stored**, so a run whose insert lost to a concurrent one cannot
go on to write consents and samples against its own discarded uuid. `ON CONFLICT DO NOTHING`
reports the loser's insert as "0 rows" without revealing the winner, and there are no foreign
keys from `consents`/`samples` back to `participants`, so nothing else would have caught it.
Inserts are also issued in sorted `source_id` order, so two runs inserting an overlapping set
of new subjects cannot deadlock by acquiring them in opposite orders.

`SstrPopulateRdsParticipantsConcurrencyIT` covers all three properties: shared subjects
converge on one uuid, no consent or sample row references a uuid with no participant, and
opposing insert orders do not deadlock.

Sequential is still the default. Loads that share subjects serialize on those rows anyway (the
loser waits for the winner's transaction to commit), so parallelism buys least exactly where
studies overlap most — and it keeps the RDS write load predictable and the console log
readable. Swapping the loop in the `Load SSTR participants` stage for a `parallel` map is a
small change if throughput matters.

**Reloading one study:** run `hpds-etl/permanent` (or the SSTR job directly) with `STUDY_ID`
set. The manifest's `ready` flag is ignored in that mode — an explicit reload should not be
blocked by a sweep flag. A reload is safe: the purge and load share one transaction, so a
failure leaves RDS exactly as it was. Expect `participantsInserted = 0` and an UNSTABLE
build, which is correct for a reload.

**Adding a study:** append a row to `studies.tsv`. Fill in `expected_consent_codes` and
`expected_min_participants` if you can — they are what catch a wrong or truncated file.

**Locally**, without Jenkins:

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

## Operations

**Where things are.** Console log per build; JSON reports archived on the build *and* left at
`s3://<stack>/etl-runner/reports/<job>/<run-id>/`; the full runner log (bootstrap plus
container output) at `s3://<stack>/etl-runner/logs/<job>-<run-id>.log`.

**Diagnosing a failure.** Read the exit code first — it says which of the five categories the
failure is in. Then `status.json`, whose `phase` field distinguishes a bootstrap failure
(`install`, `credentials`, `image`) from the job itself (`job`). Then the JSON report's
`inputValidation` / `outputValidation` issues, which name the specific rows and columns.

**A run that hangs.** The monitor times out at `JOB_TIMEOUT_SECONDS` (default 2h) and exits
124. The instance still terminates itself. Check the tail of the runner log in S3 for the last
phase reached.

**A leaked instance profile.** `terraform destroy` runs in `post { always }`, but if the build
was hard-killed the profile `<job>-etl-instance-profile-<suffix>` can survive. The instance
itself always terminates, so nothing is billed for compute. Delete the profile by hand or
re-run `make destroy` with the same `STATE_KEY`.

**Cost.** One instance per job run, alive only for the job, terminated by itself. Per-build
image tarballs are removed from S3 in `post { always }`.

**Turning a job off.** Jobs are opt-in: each is registered only where
`etl.jobs.<job-name>.enabled` is `true` (see `etl.jobs` in
[`application.yml`](../src/main/resources/application.yml)). The shipped defaults enable the
three real jobs and disable `template`, so the pipelines work as documented with no override.

To disable a job for a single run without a code change, set its environment variable in the
runner's container environment — `ETL_JOB_PARTICIPANTS_MIGRATION_ENABLED=false`, etc. The job
then exits `5` (`CONFIG_ERROR`) with a message naming the flag, rather than running.

Two consequences worth knowing:

- `participants-migration` also requires `etl.jobs.sstr-populate-rds-participants.enabled`,
  because it injects that job to load the sstr-backed studies. Both flags are on its
  condition, so disabling the sstr job makes the migration job disappear cleanly instead of
  breaking Spring context startup on a missing bean.
- This is the intended retirement path for the migration: set
  `ETL_JOB_PARTICIPANTS_MIGRATION_ENABLED=false` everywhere and confirm nothing calls it,
  *then* delete the job, its runner directory, and `/Jenkinsfile`.

## Known gaps

- **No post-load assertion against RDS itself.** Everything after the tests reasons about
  report metrics, which are counts of rows the repositories affected — strong, but not a
  `SELECT` against the loaded table. The clean fix is a small `verify-rds-participants` job in
  the JAR that the pipeline runs as a second container on the same runner, since only the
  runner can reach RDS. Adding `builder.metric("studyId", studyId)` to
  `SstrPopulateRdsParticipantsJob.report` would also let `validate.sh` confirm a report belongs
  to the study it was asked about, rather than inferring it from the run id.
- **`studies.tsv` ships with example rows only.** A sweep fails with a clear message until it
  is populated.
- **`instance_type` is a guess per job.** Both jobs hold their input in memory
  (`List<Telemetry>` for SSTR, the mapping and consents joins for the migration), so the
  ceiling is the largest file, not the average. Watch the first real runs and set
  `instance_type` per study in `studies.tsv` where needed.
