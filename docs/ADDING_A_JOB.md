# Adding a new job

Jobs are "plug and play": a job is any `@Component` that extends `AbstractJob<O>`. There
is **no central registry to edit** — `JobRegistry` discovers every `Job` bean on the
classpath and indexes it by `name()`, so a new job is runnable via
`--job=<name>` the moment it compiles.

## 1. Copy the template

Copy [`TemplateJob`](../src/main/java/edu/harvard/hms/dbmi/avillach/hpds/etl/jobs/template/TemplateJob.java)
into a new package and rename it:

- **Permanent jobs** → `edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.<area>`, `type() = PERMANENT`
- **Temporary migrations** → `...etl.jobs.migration`, `type() = MIGRATION` (delete once rolled out everywhere)

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
