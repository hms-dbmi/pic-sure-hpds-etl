# Participant Database

The `participants`, `consents`, and `samples` tables (schema `etl`) live in a Postgres server
that exists only while a pipeline runs. Two Jenkins jobs start and stop it. Between runs, the
data is kept as `pg_dump` archives in S3.

Stack: [`etl-runners/participant-db/`](../etl-runners/participant-db/). Schema reference:
[`schema.sql`](../src/main/resources/repository/schema.sql).

## Table of Contents

- [Lifecycle](#lifecycle)
- [Jobs](#jobs)
- [Where the Dumps Live](#where-the-dumps-live)
- [Supplying the Initial Dump](#supplying-the-initial-dump)
- [Restore Rules](#restore-rules)
- [Credentials](#credentials)
- [Running a DB-Backed Runner Standalone](#running-a-db-backed-runner-standalone)
- [Debugging a Failed Run](#debugging-a-failed-run)
- [When a Database Is Already Running](#when-a-database-is-already-running)
- [Recovery and Troubleshooting](#recovery-and-troubleshooting)

---

## Lifecycle

```
orchestrator (/Jenkinsfile or /Jenkinsfile.migration)
  Build ▸ Tests ▸ (Resolve studies)
  ▸ Start participant DB ── participant-db-start
  │     terraform apply: secret (empty), security group, instance profile, EC2 instance
  │     instance boot:   install Postgres ▸ restore backups/participant_db.tar (or fresh schema)
  │                      ▸ write host/port/dbname/username/password into the secret
  │                      ▸ status.json {status: ready}
  ▸ … DB-backed runner stages (each reads the secret at boot) …
  post { always }
  ▸ participant-db-stop  (only if this build started the database, and skipped if the build
  │                       FAILED with KEEP_DB_ON_FAILURE=true: see Debugging a Failed Run)
        SSM: pg_dump --format=d ▸ tar ▸ upload ▸ verify size and dumped-at
             SUCCESS or UNSTABLE:  overwrite participant_db.tar               (the restore point)
             otherwise:            overwrite participant_db_not_promoted.tar
        terraform destroy: instance, security group, profile, secret, secret-access policy
```

| Property | How it holds |
|----------|--------------|
| **One database per environment** | The Terraform state key is fixed per environment (`tf_backend/etl-runners/hpds-etl/participant-db/<ENV>/terraform.tfstate`). A start that finds an instance in that state reuses it or replaces it; it never creates a second one. See [When a Database Is Already Running](#when-a-database-is-already-running). |
| **A failed run never becomes the restore point** | Every stop dumps the database, but only a SUCCESS/UNSTABLE build overwrites `participant_db.tar`. A failed or aborted run's dump goes to `participant_db_not_promoted.tar` instead, and is restored only if someone asks for it with `RESTORE_FROM`. |
| **No write is lost to a failed backup** | If the dump or its verification fails, the stop job fails **without destroying anything**. The database stays up until a stop succeeds. |
| **A half-built database is never published** | The secret gets a value only after the restore succeeds, so the runners and `require-db.sh` treat "secret has a current value" as "database ready". |
| **A failed run can be debugged** | With `KEEP_DB_ON_FAILURE=true` (the default), a FAILED build does not stop the database. It stays up, holding the run's writes, until someone runs `participant-db-stop`. Aborted builds are torn down as usual. |
| **An orchestrator stops the database it used** | `post { always }` calls the stop job only if *this* build's start succeeded, whether it started the database or reused one. A failed start leaves any database alone. |

PREFLIGHT_ONLY runs start no database.

---

## Jobs

Both live in the `hpds-etl` Jenkins folder.

| Jenkins job | Script path | Parameters |
|-------------|-------------|------------|
| `participant-db-start` | `etl-runners/participant-db/Jenkinsfile.start` | `RESTORE_FROM` (see [Restore Rules](#restore-rules)), `INSTANCE_TYPE` (default `m5.xlarge`), `RESTART_IF_RUNNING` (default `false`: reuse a running database; see [When a Database Is Already Running](#when-a-database-is-already-running)), `BACKUP_TIMEOUT_SECONDS` (restart only), `READY_TIMEOUT_SECONDS`, `RUN_ID`, `ENV` |
| `participant-db-stop` | `etl-runners/participant-db/Jenkinsfile.stop` | `PROMOTE_BACKUP` (default `true`), `SKIP_BACKUP` (default `false`, **discards writes**), `BACKUP_TIMEOUT_SECONDS`, `ENV` |

The orchestrators call these jobs through their `DB_START_JOB` and `DB_STOP_JOB` parameters.

The same steps can be run locally from `etl-runners/participant-db/`:

```bash
export TF_VAR_run_id=local-1                # TF_VAR_restore_from=none for a fresh schema
make init apply wait-ready                  # start
make init backup PROMOTE=false destroy      # stop, leaving participant_db.tar as it was
```

---

## Where the Dumps Live

```
s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/
├─ participant_db.tar                the restore point: what the next start restores
└─ participant_db_not_promoted.tar   the most recent failed or aborted run's dump
```

There are only ever these two files. Each stop overwrites one of them, and the bucket's
versioning keeps the dumps they replaced. Every upload carries `dumped-at` metadata (its UTC
timestamp), which identifies a version. `participant-db-stop` refuses to back up if the bucket's
versioning is not `Enabled`, because the overwrite would then destroy the previous dump.

Each `.tar` contains one directory (`participant_db/` or `participant_db_not_promoted/`),
produced by:

```bash
pg_dump --format=d --jobs=10 --verbose \
   --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
   --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
   --schema etl etl_db
```

These flags leave out everything tied to one server (owners, grants, tablespaces, publications).
That lets a dump restore cleanly into a fresh server under the app role.

### Older dumps

To list them:

```bash
aws s3api list-object-versions --bucket bdc-etl-data-d0d6191 \
  --prefix avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/participant_db.tar \
  --query 'Versions[].[VersionId, LastModified, Size, IsLatest]' --output table
```

To make an older dump the restore point again, copy that version over the current one. The copy
becomes a new version, so nothing is lost:

```bash
B=bdc-etl-data-d0d6191
K=avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/participant_db.tar
aws s3api copy-object --bucket $B --key $K --copy-source "$B/$K?versionId=<VersionId>"
```

To restore one just once, without changing the restore point, download that version, upload it
under another name in `backups/`, and pass that name as `RESTORE_FROM`.

Old versions accumulate. If retention matters, add an S3 lifecycle rule that expires noncurrent
versions of these keys after some days.

### Environments from before the single dump

Before this layout, each stop wrote a timestamped `participant_db_<timestamp>.tar`, and a
`LATEST` file named the one to restore. Until `participant_db.tar` exists, a start still
restores the dump `LATEST` names. The first promoted stop writes `participant_db.tar`. After
that, delete `LATEST` and the timestamped dumps. Or, to switch over before the next run, copy
the dump `LATEST` names to `participant_db.tar`.

Boot logs and status files sit next to the runners' own:
`s3://bdc-etl-data-d0d6191/etl-runner/logs/participant-db-<ENV>-<run-id>.log` and
`s3://bdc-etl-data-d0d6191/etl-runner/participant-db/<ENV>/<run-id>/status.json`.

---

## Supplying the Initial Dump

The first database in a new environment has nothing to restore. To carry the existing
participants (and their integer `hpds_id`s) over from the old RDS instance:

1. **Take the dump** from the source database, with the same flags as above and
   `--schema etl`. The schema must be called `etl`; the restore checks for it.

   ```bash
   PGPASSWORD="…" pg_dump --host=<source-host> --port=5432 --username=<user> \
      --file=participant_db_seed --format=d --jobs=10 --verbose \
      --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
      --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
      --schema etl <source-dbname>
   tar -cvf participant_db_seed.tar participant_db_seed
   ```

   Use a `pg_dump` no newer than PostgreSQL 16 (the server's `pg_version`). A source newer
   than 16 needs `pg_version` raised to match.

2. **Upload it as the restore point.** The directory inside the tar can have any name; the
   restore looks for its `toc.dat`.

   ```bash
   aws s3 cp participant_db_seed.tar \
     s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/development/backups/participant_db.tar
   ```

   The first promoted `participant-db-stop` replaces it with a regular dump, and the seed stays
   available as an older version.

The `hpds_id_seq` sequence's current value travels with the dump (`pg_dump` emits its
`setval`), so new participants keep receiving ids above the migrated ones.

If `participant_db.tar` does not exist, a start creates an empty `etl` schema from `schema.sql`. That is correct
for a brand-new environment and **wrong** for one with history. Seed before the first real run.

---

## Restore Rules

`participant-db-start`'s `RESTORE_FROM`:

| Value | Restores |
|-------|----------|
| blank (default; what the orchestrators use) | `backups/participant_db.tar`; a fresh schema if it does not exist |
| `none` | a fresh schema from `schema.sql`, even if a dump exists |
| a file name, e.g. `participant_db_not_promoted.tar` | that file under `backups/` |
| an `s3://…` URI | that object (any tar of a `pg_dump --format=d` directory) |

The restore uses `pg_restore --format=d --jobs=$(nproc) --no-owner --no-privileges
--exit-on-error`, run as the app role `hpds_etl`, which therefore owns everything restored. A
restore error fails the start, and the start job then destroys the half-built instance. Nothing
has written to it, and its source is still in S3.

---

## Credentials

- The stack creates the Secrets Manager secret (`db_secret_id` in
  `environments/<ENV>.tfvars`, `hpds-etl-development-participant-db`) **empty**, with
  `recovery_window_in_days = 0`. The destroy removes it immediately, so the next start can
  reuse the name.
- The instance generates the password at boot, creates the `hpds_etl` role with it, and writes
  `{engine, host, port, dbname, schema, username, password}` into the secret once ready. The
  password never appears in Terraform state, user data, or the Jenkins console.
- While the database exists, the stack attaches an inline policy (`…-secret`) to
  `bdc-etl-jenkins-role`. It grants `GetSecretValue`/`DescribeSecret`/`PutSecretValue` on that
  secret only, and the destroy removes it.
- Runners fetch the secret at boot and pass `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` to the
  container through a `600`-mode env file. `merge-allconcepts` and
  `generate-identity-consent-mapping` touch no database and skip the fetch.
- Network: the DB instance gets its own security group, which allows `5432` only from the
  runners' security groups. `pg_hba.conf` allows the app role from the VPC CIDR, and the
  `postgres` superuser over the local socket only.

---

## Running a DB-Backed Runner Standalone

Runners that use the database (`sstr-populate-rds-participants`, `participants-migration`,
`split-allconcepts`, `generate-global-all-concepts`, `create-vcf-indexes`,
`all-concepts-data-generator`) check for it first. Their
[`require-db.sh`](../etl-runners/common/require-db.sh) call fails within seconds, before
anything is provisioned, when the database is down. To run one by hand:

1. `participant-db-start`
2. the runner job(s)
3. `participant-db-stop`, with `PROMOTE_BACKUP=true` if the writes should become the next restore
   point. Don't skip this: the instance otherwise keeps running, and its writes exist nowhere
   else.

---

## Debugging a Failed Run

Both orchestrators have `KEEP_DB_ON_FAILURE`, `true` by default. When a build ends FAILURE, its
`post` skips `participant-db-stop`: nothing is dumped and nothing is destroyed, and the build
description says the database was left running. (SUCCESS and UNSTABLE builds stop as normal, and
so do ABORTED ones. Set the parameter to `false` to always stop.)

1. Connect: `aws ssm start-session --target <instance id>`, then `sudo -u postgres psql etl_db`.
   The instance is named `hpds-etl-participant-db-<ENV>`. Runner jobs can also be re-run
   against it, since the secret still exists.
2. When finished, run `participant-db-stop` with `PROMOTE_BACKUP=false`. It dumps to
   `participant_db_not_promoted.tar` and destroys the instance; `participant_db.tar` is
   untouched. Use `PROMOTE_BACKUP=true` only if you fixed the data and want it to be the next
   restore point.

Until the stop runs, the instance keeps costing money. A pipeline run in the meantime reuses
it, picking up where the failed run left off; set `RESTART_DB_IF_RUNNING=true` to start from
the restore point instead.

---

## When a Database Is Already Running

A start that finds an instance in the environment's state (kept by a failed build, left by a
manual run, or in use by another pipeline) does not create a second one. What it does depends on
`RESTART_IF_RUNNING` (`RESTART_DB_IF_RUNNING` on the orchestrators):

| Setting | What the start does |
|---------|---------------------|
| `false` (default) | **Reuses it.** Checks that the instance is `running` and its secret is published, and provisions nothing. `RESTORE_FROM` and `INSTANCE_TYPE` are ignored. The database keeps every write already in it. |
| `true` | **Replaces it.** Dumps it to `participant_db_not_promoted.tar` (`participant_db.tar` is untouched), destroys it, then starts a new one from `RESTORE_FROM` as usual. If the dump fails, nothing is destroyed and the start fails. |

A pipeline that reuses a database owns it from then on: its `post` stops it, and promotes the
dump to `participant_db.tar` on success, as for a database it started. The dump then includes
any writes the earlier run left in it.

**Don't run two pipelines at once in the same environment.** The second reuses the first's
database (or, with `RESTART_DB_IF_RUNNING=true`, replaces it under the first). Whichever
finishes first then stops it while the other is still using it.

---

## Recovery and Troubleshooting

| Symptom | Meaning / action |
|---------|------------------|
| Start fails: instance is *"stopped"*, *"not running"* | A database is in the state but its instance is not running, so it cannot be reused. Start it (`aws ec2 start-instances`) and re-run, or run `participant-db-stop`. **Do not** delete the state or the instance: it may hold the only copy of a run's writes. |
| Start fails in *Replace running DB* | `RESTART_IF_RUNNING` could not dump the running database, so it was left up, untouched. Fix the cause (see the SSM output) and re-run, or run `participant-db-stop`. |
| Start fails in `wait-ready` | The console prints the tail of the boot log and the failed `phase` (`install`, `configure`, `restore`, `publish`). The start job has already destroyed the instance. A `restore` failure usually means the dump is not a tar of a `--format=d` directory, or has no `etl` schema. |
| Stop fails in *Back up* | The database is **still up** and nothing was destroyed. The SSM output in the console shows the `pg_dump`/upload error. If the error is that bucket versioning is not `Enabled`, enable it. Fix the cause and re-run `participant-db-stop`. |
| Stop fails in *Tear down* | The dump is already safe in S3 (and promoted if requested). Re-run `participant-db-stop`. With no instance left it only finishes the destroy. Use `SKIP_BACKUP=true` only if the backup stage cannot run again. |
| Instance found **stopped** | `instance_initiated_shutdown_behavior` is `stop`, so the volume survives an accidental shutdown. Start the instance (EC2 console or `aws ec2 start-instances`), wait for SSM to come online, then run `participant-db-stop`. |
| Runner exits `5` in phase `credentials` | The secret was missing when the runner booted, i.e. the database went down mid-pipeline. Check the orchestrator's stop/start history. |
| Need yesterday's data | It is an older version of `participant_db.tar`. See [Older dumps](#older-dumps). |
