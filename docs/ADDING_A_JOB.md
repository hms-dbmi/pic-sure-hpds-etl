# Adding a new job

Jobs are "plug and play": a job is any `@Component` that extends `AbstractJob<O>`. There
is **no central registry to edit** — `JobRegistry` is built from the `Job` beans Spring found
and indexes them by `name()`, so there is no switch statement or list of classes anywhere.

Jobs are, however, **opt-in**. Each carries
`@ConditionalOnProperty("etl.jobs.<name>.enabled", havingValue = "true")`, so a job with no
flag is never instantiated and never reaches the registry. Wiring a new job therefore means
one line of configuration, not one line of code.

## 1. Copy the template

Copy [`TemplateJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/template/TemplateJob.java)
into a new package and rename it:

- **Permanent jobs** → `edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.<area>`, `type() = PERMANENT`
- **Temporary migrations** → `...etl.jobs.migration`, `type() = MIGRATION` (delete once rolled out everywhere)

Update the copied `@ConditionalOnProperty` to your job's `name()`, then add the flag under
`etl.jobs` in [`application.yml`](../src/main/resources/application.yml):

```yaml
etl:
  jobs:
    my-new-job:
      enabled: ${ETL_JOB_MY_NEW_JOB_ENABLED:true}
```

Until that flag is `true`, `--job=my-new-job` exits `5` with a message naming the flag. If
your job **injects another job** (as `ParticipantsMigrationJob` injects
`SstrPopulateRdsParticipantsJob`), list both flags in the condition — otherwise disabling the
dependency stops the Spring context from starting at all, instead of just making your job
unavailable.

## 2. Fill in the five hooks

`AbstractJob` fixes the lifecycle; you only implement the parts that differ:

| Hook | Purpose |
|------|---------|
| `expectations()` | Declare input params (`ParamSpec`) and outputs. Drives auto-validation of required params and `--help`. |
| `validateInput(ctx, report)` | Cheap pre-checks. Add `report.error(...)` to block execution before any work is done. |
| `execute(ctx)` | The actual extract/transform/load. Return a value describing what happened. Throw a typed exception on failure (below). |
| `validateOutput(out, ctx, report)` | Post-condition assertions on the result. Add `report.error(...)` to fail the run. |
| `report(out, builder)` | Attach metrics (row counts, timings) to the JSON report Jenkins archives. |

## 3. Signal failure with the right exception → exit code

Throw from `execute(...)`; `JobExecutor` maps each to a deterministic process exit code
that Jenkins gates on:

| Throw | Exit code | Meaning |
|-------|-----------|---------|
| `ConfigException` | 5 `CONFIG_ERROR` | missing/invalid param or credential |
| validation `report.error(...)` | 2 `VALIDATION_FAILED` | input/output validation failed |
| `DataException` | 3 `DATA_ERROR` | input reachable but malformed |
| `InfrastructureException` | 4 `INFRASTRUCTURE_ERROR` | RDS/S3/network failed (retryable) |
| *(anything else)* | 1 `UNKNOWN` | unclassified |

## 4. Reuse the building blocks

Inject these instead of rolling your own:

- **`IoResolver`** — `openInput(uri)` / `writeOutput(uri, bytes)` transparently for `s3://` or local paths.
- **`DelimitedReader`** — stream CSV/TSV rows as `Map<String,String>` (memory-flat).
- **`JsonReader`** — parse JSON into domain types.
- **`ParticipantRepository` / `ConsentRepository` / `SampleRepository`** — batched, idempotent upserts.
- **`PlatformTransactionManager`** (via `TransactionTemplate`) — make a multi-batch load atomic.

### Resolving participant uuids: use `resolveOrCreate`

Call **`ParticipantRepository.resolveOrCreate(sourceIds, source, batchSize)`** whenever you need
a participant's uuid. Do **not** hand-roll `findUuids` + `batchUpsert`:

```java
ParticipantRepository.Resolution r = participants.resolveOrCreate(subjectIds, SOURCE, batchSize);
Map<String, UUID> uuidBySubject = r.uuidsBySourceId();   // the uuids actually in the table
long inserted = r.inserted();                            // 0 on a reload, which is correct
```

`batchUpsert` inserts with `ON CONFLICT DO NOTHING`, which reports a losing insert as "0 rows"
without revealing the uuid that won. A job that kept its own generated uuid would then write
consents and samples against a uuid with no `participants` row — one person, two identities —
and nothing would catch it, because there are no foreign keys back to `participants`. This is a
real hazard for any job sharing a `source` with a concurrent run: every SSTR study load uses
`source = "DBGap"`, so two studies with a subject in common race for it.

`resolveOrCreate` re-reads after inserting and returns the stored uuid, and inserts in sorted id
order so concurrent callers cannot deadlock on an overlapping set of new subjects. See
`ParticipantRepositoryIT` for the SQL semantics and
`SstrPopulateRdsParticipantsConcurrencyIT` for the end-to-end guarantee.

### Boolean parameters

`JobContext.getBoolean` rejects anything that is not a recognised literal
(`true/yes/y/1/on`, `false/no/n/0/off`) rather than following `Boolean.parseBoolean`, which
silently maps a typo like `treu` to `false`. Also check it in `validateInput` with
`JobContext.isBooleanLiteral(raw)` so a bad flag appears in the JSON report before any work
starts, rather than surfacing as a thrown exception later.

## 5. Write tests — success **and** every failure

Every business case gets a test; every failure mode gets a test.

- **Unit** (fast, no Docker): construct the job directly with real readers and an
  `IoResolver(null)` over local temp files. See
  [`TemplateJobTest`](../src/test/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/template/TemplateJobTest.java).
  Use `JobTestSupport.tempFile(...)` / `JobTestSupport.context(...)`.
- **Integration** (real Postgres + LocalStack S3): extend `AbstractIntegrationTest`,
  `@Autowired` the job + `JobExecutor` + repositories, assert DB state. See
  [`ParticipantsMigrationJobIT`](../src/test/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/migration/ParticipantsMigrationJobIT.java).

Adding a newly-discovered edge case = one more `@Test` method.

## 6. Wire it into a pipeline

- **Local/CI:** add the job name to a list under `etl.pipelines.<name>` in
  `application.yml` and run `--pipeline=<name>`.
- **Production:** the job needs an **ephemeral runner** and a **stage in the right
  orchestrator**. Which orchestrator follows directly from `type()`:

  | `type()` | Orchestrator | Note |
  |---|---|---|
  | `PERMANENT` | [`/Jenkinsfile.permanent`](../Jenkinsfile.permanent) | the ongoing ingestion surface |
  | `MIGRATION` | [`/Jenkinsfile`](../Jenkinsfile) | deleted with the migration |

  Copy an existing runner directory and adapt it —
  [`etl-runners/README.md`](../etl-runners/README.md) has the seven steps. The runner owns
  provisioning, monitoring, and validating; the orchestrator stage just triggers it with
  `build job:`, so the next stage runs only if yours exited `0`.

  Two things to get right in the runner:

  - **`preflight.sh`** — check what is knowable from the inputs alone, before an instance
    exists. Every failure you can catch here costs seconds instead of a provisioned runner.
  - **`validate.sh`** — assert *invariants*, not plausible-looking numbers. Read how the
    repository upserts first: `ON CONFLICT DO NOTHING` returns only newly-inserted rows (so
    the count is legitimately `0` on a re-run), while `ON CONFLICT DO UPDATE` reports every
    row. Only the latter supports an equality assertion.

  See [`docs/JENKINS.md`](JENKINS.md) for the full architecture and the exit-code contract.
