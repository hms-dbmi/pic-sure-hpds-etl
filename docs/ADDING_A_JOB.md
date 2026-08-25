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
| `InfrastructureException`      | 4 `INFRASTRUCTURE_ERROR` | RDS/S3/network failed (retryable)   |
| *(anything else)*              | 1 `UNKNOWN`              | unclassified                        |

## 4. Reuse the Building Blocks

Inject these rather than rolling your own.

| Component                                                        | Purpose                                                             |
|------------------------------------------------------------------|---------------------------------------------------------------------|
| `IoResolver`                                                     | `openInput(uri)` / `writeOutput(uri, bytes)` for `s3://` or local paths |
| `DelimitedReader`                                                | Stream CSV/TSV rows as `Map<String,String>` (memory-flat)            |
| `JsonReader`                                                     | Parse JSON into domain types                                        |
| `ParticipantRepository` / `ConsentRepository` / `SampleRepository` | Batched, idempotent upserts                                         |
| `PlatformTransactionManager` (via `TransactionTemplate`)          | Make a multi-batch load atomic                                      |

### Participant UUIDs

Use `ParticipantRepository.resolveOrCreate(sourceIds, source, batchSize)` whenever a
participant's uuid is needed. Do not hand-roll `findUuids` + `batchUpsert`.

```java
ParticipantRepository.Resolution r = participants.resolveOrCreate(subjectIds, SOURCE, batchSize);
Map<String, UUID> uuidBySubject = r.uuidsBySourceId();   // the uuids actually in the table
long inserted = r.inserted();                            // 0 on a reload
```

`batchUpsert` inserts with `ON CONFLICT DO NOTHING`, which reports a losing insert as "0 rows"
without revealing the uuid that won. A job keeping its own generated uuid would write consents
and samples against a uuid with no `participants` row, and nothing would catch it — there are no
foreign keys back to `participants`. The hazard applies to any job sharing a `source` with a
concurrent run; every SSTR study load uses `source = "DBGap"`.

`resolveOrCreate` re-reads after inserting and returns the stored uuid, and inserts in sorted id
order so concurrent callers cannot deadlock on an overlapping set of new subjects. See
`ParticipantRepositoryIT` for the SQL semantics and `SstrPopulateRdsParticipantsConcurrencyIT`
for the end-to-end guarantee.

### Boolean Parameters

`JobContext.getBoolean` accepts `true/yes/y/1/on` and `false/no/n/0/off` and rejects anything
else. It does not use `Boolean.parseBoolean`, which maps an unrecognised string such as `treu`
to `false`, silently inverting a flag.

Also check the value in `validateInput` with `JobContext.isBooleanLiteral(raw)` so a bad flag
appears in the JSON report before any work starts, rather than surfacing later as a thrown
exception.

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

**Production:** the job needs an ephemeral runner and a stage in the orchestrator matching its
`type()`.

| `type()`    | Orchestrator                                         | Lifetime                      |
|-------------|------------------------------------------------------|-------------------------------|
| `PERMANENT` | [`/Jenkinsfile.permanent`](../Jenkinsfile.permanent) | the ongoing ingestion surface |
| `MIGRATION` | [`/Jenkinsfile`](../Jenkinsfile)                     | deleted with the migration    |

Copy an existing runner directory and adapt it; [`etl-runners/README.md`](../etl-runners/README.md)
lists the steps. The runner owns provisioning, monitoring, and validation; the orchestrator stage
triggers it with `build job:`, so the next stage runs only if yours exited `0`.

Two things to get right in the runner:

- **`preflight.sh`** — check what is knowable from the inputs alone, before an instance exists.
  A failure caught here costs seconds instead of a provisioned runner.
- **`validate.sh`** — assert invariants rather than expected-looking numbers. Check how the
  repository upserts first: `ON CONFLICT DO NOTHING` returns only newly inserted rows, so the
  count is legitimately `0` on a re-run, while `ON CONFLICT DO UPDATE` reports every row. Only
  the latter supports an equality assertion.

See [`docs/JENKINS.md`](JENKINS.md) for the architecture and the exit-code contract.
