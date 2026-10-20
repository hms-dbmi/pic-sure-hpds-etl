# Adding a New Job

A job is any `@Component` that extends `AbstractJob<O>`. There is no central registry to edit:
`JobRegistry` is built from the `Job` beans Spring found and indexes them by `name()`, so no
switch statement or list of classes exists anywhere.

Jobs are opt-in. Each carries
`@ConditionalOnProperty("etl.jobs.<name>.enabled", havingValue = "true")`, so a job with no flag
is never instantiated and never reaches the registry. Wiring a new job is one line of
configuration rather than one line of code.

## Table of Contents

- [1. Copy the Template](#1-copy-the-template)
- [2. Fill In the Five Hooks](#2-fill-in-the-five-hooks)
- [3. Signal Failure With the Right Exception](#3-signal-failure-with-the-right-exception)
- [4. Reuse the Building Blocks](#4-reuse-the-building-blocks)
- [5. Write Tests](#5-write-tests)
- [6. Wire It Into a Pipeline](#6-wire-it-into-a-pipeline)

---

## 1. Copy the Template

Copy [`TemplateJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/template/TemplateJob.java)
into a new package and rename it:

| `type()`    | Package                                                | Lifetime                          |
|-------------|--------------------------------------------------------|-----------------------------------|
| `PERMANENT` | `edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.<area>`   | ongoing                           |
| `MIGRATION` | `edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration` | delete once rolled out everywhere |

Update the copied `@ConditionalOnProperty` to the job's `name()`, then add the flag under
`etl.jobs` in [`application.yml`](../src/main/resources/application.yml):

```yaml
etl:
  jobs:
    my-new-job:
      enabled: ${ETL_JOB_MY_NEW_JOB_ENABLED:true}
```

Until that flag is `true`, `--job=my-new-job` exits `5` with a message naming the flag.

If the job injects another job — as `ParticipantsMigrationJob` injects
`SstrPopulateRdsParticipantsJob` — list both flags in the condition. Otherwise disabling the
dependency prevents the Spring context from starting at all rather than making one job
unavailable.

## 2. Fill In the Five Hooks

`AbstractJob` fixes the lifecycle; implement only the parts that differ.

| Hook                               | Purpose                                                                                                                 |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `expectations()`                   | Declare input params (`ParamSpec`) and outputs. Drives auto-validation of required params and `--help`.                  |
| `validateInput(ctx, report)`       | Cheap pre-checks. Add `report.error(...)` to block execution before any work is done.                                   |
| `execute(ctx)`                     | The extract/transform/load. Return a value describing what happened. Throw a typed exception on failure.                 |
| `validateOutput(out, ctx, report)` | Post-condition assertions on the result. Add `report.error(...)` to fail the run.                                       |
| `report(out, builder)`             | Attach metrics (row counts, timings) to the JSON report Jenkins archives.                                                |

## 3. Signal Failure With the Right Exception

Throw from `execute(...)`. `JobExecutor` maps each exception to a deterministic process exit code
that Jenkins gates on.

| Throw                          | Exit code                | Meaning                             |
|--------------------------------|--------------------------|-------------------------------------|
| `ConfigException`              | 5 `CONFIG_ERROR`         | missing/invalid param or credential |
| validation `report.error(...)` | 2 `VALIDATION_FAILED`    | input/output validation failed      |
| `DataException`                | 3 `DATA_ERROR`           | input reachable but malformed       |
| `InfrastructureException`      | 4 `INFRASTRUCTURE_ERROR` | DB/S3/network failed (retryable)    |
| *(anything else)*              | 1 `UNKNOWN`              | unclassified                        |

A thrown exception normally leaves the report with just the error. A job that fails part-way but
has already established something worth reporting attaches it:
`throw new InfrastructureException(msg).withPartialResult(metrics, outputValidation)`, and
`JobExecutor` keeps those metrics and issues in the failed run's report (see `QaConsentCountsJob`,
which still reports every study it checked when dbGaP stops answering).

## 4. Reuse the Building Blocks

Inject these rather than rolling your own.

| Component                                                        | Purpose                                                             |
|------------------------------------------------------------------|---------------------------------------------------------------------|
| `IoResolver`                                                     | `s3://` or local paths: `openInput(uri)`, `exists`, `size(uri)`, `delete(uri)`; `writeOutput(uri, bytes)` for small outputs only; `writeOutputFile(uri, file)` / `writeOutputFile(uri, file, Map metadata)` (returns the version id) and `writeOutputStream(uri, length, metadata, content)` for large ones (multipart, nothing held in memory); `requireVersioning(uri)` before overwriting or deleting in place, so every earlier version stays recoverable |
| `DelimitedReader`                                                | Stream CSV/TSV rows as `Map<String,String>` (memory-flat)            |
| `JsonReader`                                                     | Parse JSON into domain types                                        |
| `ParticipantRepository` / `ConsentRepository` / `SampleRepository` | Batched, idempotent upserts                                         |
| `ConsentRepository.KEY_ORDER` / `SampleRepository.KEY_ORDER` + `BatchOps.upsertInChunks(upsert, items, batchSize, keyOrder)` | Upsert in a fixed key order, so concurrent loads touching overlapping rows lock them in the same order and cannot deadlock. Use the `keyOrder` overload for any table more than one study load can write at once |
| `PlatformTransactionManager` (via `TransactionTemplate`)          | Make a multi-batch load atomic                                      |

### Participant IDs

HPDS ids are integers drawn from the shared `hpds_id_seq` sequence. Use
`ParticipantRepository.resolveOrCreate(sourceIds, source, batchSize)` whenever a participant's
id is needed. Do not hand-roll `findIds` + `batchUpsert`.

```java
ParticipantRepository.Resolution r = participants.resolveOrCreate(subjectIds, SOURCE, batchSize);
Map<String, Long> idBySubject = r.idsBySourceId();   // the ids actually in the table
int inserted = r.inserted();                         // 0 on a reload
```

`batchUpsert` inserts with `ON CONFLICT DO NOTHING`, which reports a losing insert as "0 rows"
without revealing the id that won. A job assuming its own insert succeeded would write consents
and samples against an id with no `participants` row, and nothing would catch it — there are no
foreign keys back to `participants`. The hazard applies to any job sharing a `source` with a
concurrent run; every SSTR study load uses `source = "DBGap"`.

`resolveOrCreate` re-reads after inserting and returns the stored id, and inserts in sorted id
order so concurrent callers cannot deadlock on an overlapping set of new subjects. See
`ParticipantRepositoryIT` for the SQL semantics and `SstrPopulateRdsParticipantsConcurrencyIT`
for the end-to-end guarantee.

### Boolean Parameters

`JobContext.getBoolean` accepts `true/yes/y/1/on` and `false/no/n/0/off` and rejects anything
else. It does not use `Boolean.parseBoolean`, which maps an unrecognised string such as `treu`
to `false`, silently inverting a flag.

Call `AbstractJob.validateBooleanParam(ctx, report, "<name>")` from `validateInput` for every
boolean parameter: a value that is present but not one of those literals is reported as
`BAD_BOOLEAN` (exit 2) before any work starts, rather than being read as false or surfacing
mid-run as a `CONFIG_ERROR`.

## 5. Write Tests

Every business case gets a test; every failure mode gets a test.

| Kind            | Setup                                                                                                  | Reference                                                                                                                        |
|-----------------|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| **Unit**        | Construct the job directly with real readers and an `IoResolver(null)` over local temp files. No Docker. | [`TemplateJobTest`](../src/test/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/template/TemplateJobTest.java)                   |
| **Integration** | Extend `AbstractIntegrationTest`, `@Autowired` the job, `JobExecutor`, and repositories; assert DB state. | [`ParticipantsMigrationJobIT`](../src/test/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/ParticipantsMigrationJobIT.java) |

`JobTestSupport.tempFile(...)` and `JobTestSupport.context(...)` cover the common fixtures.

## 6. Wire It Into a Pipeline

**Local/CI:** add the job name to a list under `etl.pipelines.<name>` in `application.yml` and
run `--pipeline=<name>`.

**Production:** the job needs an ephemeral runner, a Jenkins job, and (unless standalone) a
stage in the orchestrator matching its `type()`.

| `type()`    | Orchestrator                                       | Lifetime                      |
|-------------|----------------------------------------------------|-------------------------------|
| `PERMANENT` | [`/Jenkinsfile`](../Jenkinsfile)                   | the ongoing ingestion surface |
| `MIGRATION` | [`/Jenkinsfile.migration`](../Jenkinsfile.migration) | deleted with the migration    |

Copy an existing runner directory (e.g. `etl-runners/create-vcf-indexes/`) and adapt it;
[`etl-runners/README.md`](../etl-runners/README.md) lists the steps. The runner owns
provisioning, monitoring, and validation; the orchestrator stage triggers it with `build job:`
(`propagate: false`, then fail on anything but SUCCESS/UNSTABLE), so the next stage runs only
if yours succeeded.

### Runner Terraform

The runner's `terraform/main.tf` pins the account and passes the shared settings from
`environments/<ENV>.tfvars` through to the module:

```hcl
provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]
}

module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  # ...module_name, stack_s3_bucket, AMI, instance_type, job_name, run_id, image_tar...
  vpc_id                 = var.vpc_id
  subnet_id              = var.subnet_id          # blank = lowest-id subnet of vpc_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = var.iam_role_name      # bdc-etl-jenkins-role
  iam_instance_profile_name = var.iam_instance_profile_name   # bdc-etl-jenkins-temp-profile

  # DB-backed jobs only; omit both if the job touches no database.
  uses_participant_db = true
  environment         = var.environment   # -> /hpds-etl/<environment>/participant-db/app-credentials

  job_params = { output = var.output_uri }
}
```

Declare `aws_account_id`, `vpc_id`, `subnet_id`, `iam_role_name`, and `iam_instance_profile_name` in its `variables.tf`
(copy them from an existing runner). A DB-backed job also passes `environment`.

**Participant DB credentials come from SSM at runner boot**, never through Terraform:
`participant-db-start` writes them (rotated on every start) to the SSM SecureString
`/hpds-etl/<ENV>/participant-db/app-credentials`, the runner reads it when it boots (its instance
role needs `ssm:GetParameter` on it and `kms:Decrypt` via SSM; see
[`terraform-modules/etl-runner/README.md`](../terraform-modules/etl-runner/README.md)), and the
connection requires TLS. So no password lands in user data or Terraform state, and a job started
after a newer `participant-db-start` simply reads the new credentials. See
[`PARTICIPANT_DB.md`](PARTICIPANT_DB.md#credentials). There is no role to assume: the instance
role does all S3 work. A job that touches no database omits `uses_participant_db` and
`environment`, which skips the credential step so it runs whether or not the participant
database is up.

**AWS credential chain.** Jobs use the SDK's default chain (the instance role on a runner, the
agent's role in Jenkins). If you point a job or a local run at a named profile (`AWS_PROFILE`)
whose config has a `role_arn`, that profile must also say where its base credentials come from —
`source_profile = <profile>` or `credential_source = Ec2InstanceMetadata` (or `Environment`) —
otherwise the role cannot be assumed and the job fails with a credentials error, not an S3 one.

**Never put a value the Jenkinsfile sets through `TF_VAR_*` (e.g. `instance_type`) in the
runner's `.tfvars`.** A `-var-file` value outranks `TF_VAR_*`, so the Jenkins parameter would
be silently ignored. Put its default in `variables.tf` instead.

### Runner Jenkinsfile

- `choice(name: 'ENV', choices: ['development'], ...)`, defaulting `ENV` to `development`.
- No AWS profile or role parameter: the agent and the runner both act as their own role.
- A job an orchestrator calls needs `copyArtifactPermission('*')` in its `options` block, or the
  orchestrator's `copyArtifacts` of its reports fails.
- A DB-backed job calls the shared check at the top of `Provision, run and monitor`, so a
  missing database fails in seconds rather than after provisioning:

  ```groovy
  if (sh(returnStatus: true, script: "etl-runners/common/require-db.sh ${env.ENV}") != 0) {
      error('The participant database is not running. Run participant-db-start (or run this job through an orchestrator), then retry.')
  }
  ```

- Create the Jenkins job inside the **`hpds-etl`** folder, pointing at the runner's
  Jenkinsfile. Orchestrators trigger jobs by name relative to that folder.

### Orchestrator Stage

A stage whose job uses the database must sit **after** `Start participant DB`. The database is
long-lived: the orchestrator makes sure it is up there, backs it up (dumps it to S3) in
`post { always }`, and never stops it. See [`PARTICIPANT_DB.md`](PARTICIPANT_DB.md).
If the stage has no pre-flight mode of its own, skip it under `PREFLIGHT_ONLY`
(`when { expression { !params.PREFLIGHT_ONLY } }`), since the database is not started then.

In `/Jenkinsfile`, keep the stage short and call the helpers in
[`etl-runners/common/orchestrator.groovy`](../etl-runners/common/orchestrator.groovy):
`orch().runDownstream(job, parameters, 'downstream-artifacts/<name>')` for a run-wide job (copies
its reports, turns UNSTABLE/failure into this build's result), or
`orch().runPerStudyStage(title:, key:, job:, studies:, workers:, paramsFor:, onFailure:)` for one
build per study. Pass every child parameter whose value matters explicitly (`INSTANCE_TYPE`,
`JOB_TIMEOUT_SECONDS`, ...): one left out gets the default the child registered on its previous
build. Long logic belongs in the loaded file, not in `pipeline {}`, which must stay under the
JVM's 64 KB method limit. If the job's JSON report should appear in the end-of-run report, add it
to `etl-runners/pipeline-report/pipeline-report.py` and its test fixture.

Two things to get right in the runner scripts:

- **`preflight.sh`** — check what is knowable from the inputs alone, before an instance exists.
  A failure caught here costs seconds instead of a provisioned runner.
- **`validate.sh`** — assert invariants rather than expected-looking numbers. Check how the
  repository upserts first: `ON CONFLICT DO NOTHING` returns only newly inserted rows, so the
  count is legitimately `0` on a re-run, while `ON CONFLICT DO UPDATE` reports every row. Only
  the latter supports an equality assertion.

See [`docs/JENKINS.md`](JENKINS.md) for the architecture and the exit-code contract.
