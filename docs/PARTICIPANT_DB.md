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
- [Recovery and Troubleshooting](#recovery-and-troubleshooting)

---

## Lifecycle

```
orchestrator (/Jenkinsfile or /Jenkinsfile.migration)
  Build ▸ Tests ▸ (Resolve studies)
  ▸ Start participant DB ── participant-db-start
  │     terraform apply: secret (empty), security group, instance profile, EC2 instance
  │     instance boot:   install Postgres ▸ restore backups/LATEST (or fresh schema)
  │                      ▸ write host/port/dbname/username/password into the secret
  │                      ▸ status.json {status: ready}
  ▸ … DB-backed runner stages (each reads the secret at boot) …
  post { always }
  ▸ participant-db-stop  (only if this build started the database)
        SSM: pg_dump --format=d ▸ tar ▸ upload ▸ verify size
             ▸ LATEST := this dump      only if the build is SUCCESS or UNSTABLE
        terraform destroy: instance, security group, profile, secret, secret-access policy
```

| Property | How it holds |
|----------|--------------|
| **One database per environment** | The Terraform state key is fixed per environment (`tf_backend/etl-runners/hpds-etl/participant-db/<ENV>/terraform.tfstate`). A start refuses to run while that state still holds an instance. |
| **A failed run never becomes the restore point** | Every stop dumps the database, but only a SUCCESS/UNSTABLE build moves `LATEST`. A failed or aborted run's dump is kept for inspection and is restored only if someone asks for it with `RESTORE_FROM`. |
| **No write is lost to a failed backup** | If the dump or its verification fails, the stop job fails **without destroying anything**. The database stays up until a stop succeeds. |
| **A half-built database is never published** | The secret gets a value only after the restore succeeds, so the runners and `require-db.sh` treat "secret has a current value" as "database ready". |
| **An orchestrator only stops its own database** | `post { always }` calls the stop job only if *this* build's start succeeded. A start that failed because another pipeline's database was up leaves that database alone. |

PREFLIGHT_ONLY runs start no database.

---

## Jobs

Both live in the `hpds-etl` Jenkins folder.

| Jenkins job | Script path | Parameters |
|-------------|-------------|------------|
| `participant-db-start` | `etl-runners/participant-db/Jenkinsfile.start` | `RESTORE_FROM` (see [Restore Rules](#restore-rules)), `INSTANCE_TYPE` (default `m5.xlarge`), `READY_TIMEOUT_SECONDS`, `RUN_ID`, `ENV` |
| `participant-db-stop` | `etl-runners/participant-db/Jenkinsfile.stop` | `PROMOTE_BACKUP` (default `true`), `SKIP_BACKUP` (default `false`, **discards writes**), `BACKUP_TIMEOUT_SECONDS`, `ENV` |

The orchestrators call these jobs through their `DB_START_JOB` and `DB_STOP_JOB` parameters.

The same steps can be run locally from `etl-runners/participant-db/`:

```bash
export TF_VAR_run_id=local-1                # TF_VAR_restore_from=none for a fresh schema
make init apply wait-ready                  # start
make init backup PROMOTE=false destroy      # stop, leaving LATEST where it was
```

---

## Where the Dumps Live

```
s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/
├─ participant_db_2026-10-02T181500Z.tar     one per participant-db-stop (UTC timestamp)
├─ participant_db_2026-10-03T090212Z.tar
└─ LATEST                                    one line: the file name the next start restores
```

Each `.tar` contains one directory, `participant_db_<timestamp>/`, produced by:

```bash
pg_dump --format=d --jobs=10 --verbose \
   --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
   --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
   --schema etl etl_db
```

These flags leave out everything tied to one server (owners, grants, tablespaces, publications).
That lets a dump restore cleanly into a fresh server under the app role.

Nothing deletes old dumps. Add an S3 lifecycle rule on the prefix if retention matters. Keep
the dump `LATEST` points at.

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

2. **Upload it** to the backups prefix:

   ```bash
   aws s3 cp participant_db_seed.tar \
     s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/development/backups/participant_db_seed.tar
   ```

3. **Make it the restore point.** Either write `LATEST`:

   ```bash
   echo participant_db_seed.tar | aws s3 cp - \
     s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/development/backups/LATEST
   ```

   or run `participant-db-start` once with `RESTORE_FROM=participant_db_seed.tar`, then
   `participant-db-stop` with `PROMOTE_BACKUP=true`. That turns the seed into a regular
   dump and points `LATEST` at it.

The `hpds_id_seq` sequence's current value travels with the dump (`pg_dump` emits its
`setval`), so new participants keep receiving ids above the migrated ones.

If no `LATEST` exists, a start creates an empty `etl` schema from `schema.sql`. That is correct
for a brand-new environment and **wrong** for one with history. Seed before the first real run.

---

## Restore Rules

`participant-db-start`'s `RESTORE_FROM`:

| Value | Restores |
|-------|----------|
| blank (default; what the orchestrators use) | the file named by `backups/LATEST`; a fresh schema if there is no `LATEST` |
| `none` | a fresh schema from `schema.sql`, even if dumps exist |
| a file name, e.g. `participant_db_2026-10-02T181500Z.tar` | that file under `backups/` |
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

## Recovery and Troubleshooting

| Symptom | Meaning / action |
|---------|------------------|
| Start fails: *"already running"* | The state still holds an instance. Either another pipeline is using it (wait), or a stop failed earlier. In that case run `participant-db-stop`. **Do not** delete the state or the instance: it may hold the only copy of a run's writes. |
| Start fails in `wait-ready` | The console prints the tail of the boot log and the failed `phase` (`install`, `configure`, `restore`, `publish`). The start job has already destroyed the instance. A `restore` failure usually means the dump is not a tar of a `--format=d` directory, or has no `etl` schema. |
| Stop fails in *Back up* | The database is **still up** and nothing was destroyed. The SSM output in the console shows the `pg_dump`/upload error. Fix it and re-run `participant-db-stop`. |
| Stop fails in *Tear down* | The dump is already safe in S3 (and promoted if requested). Re-run `participant-db-stop`. With no instance left it only finishes the destroy. Use `SKIP_BACKUP=true` only if the backup stage cannot run again. |
| Instance found **stopped** | `instance_initiated_shutdown_behavior` is `stop`, so the volume survives an accidental shutdown. Start the instance (EC2 console or `aws ec2 start-instances`), wait for SSM to come online, then run `participant-db-stop`. |
| Runner exits `5` in phase `credentials` | The secret was missing when the runner booted, i.e. the database went down mid-pipeline. Check the orchestrator's stop/start history. |
| Need yesterday's data | Run `participant-db-start` with `RESTORE_FROM=<that dump's file name>`. |
