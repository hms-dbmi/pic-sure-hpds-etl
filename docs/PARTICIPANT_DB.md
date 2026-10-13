# Participant Database

The `participants`, `consents`, and `samples` tables (schema `etl`) live in a **long-lived
PostgreSQL 16 container on the Jenkins host**. It stays up between pipeline runs, and every
pipeline run ends with a backup to S3.

Scripts: [`etl-runners/participant-db/`](../etl-runners/participant-db/) (`lib.sh` holds the
logic and names, `start.sh` and `backup.sh` the two jobs, `participant-db.conf` the settings).
Schema reference: [`schema.sql`](../src/main/resources/repository/schema.sql).

## Table of Contents

- [How It Runs](#how-it-runs)
- [Lifecycle](#lifecycle)
- [Jobs](#jobs)
- [Start Rules](#start-rules)
- [Where the Dumps Live](#where-the-dumps-live)
- [Prerequisites on the Jenkins Host](#prerequisites-on-the-jenkins-host)
- [Switching Over from the EC2 Database](#switching-over-from-the-ec2-database)
- [Supplying the Initial Dump](#supplying-the-initial-dump)
- [Credentials](#credentials)
- [Running a DB-Backed Runner Standalone](#running-a-db-backed-runner-standalone)
- [Recovery and Troubleshooting](#recovery-and-troubleshooting)
- [Tests](#tests)

---

## How It Runs

Jenkins itself runs in a podman container, with the host's podman socket mounted as
`/var/run/docker.sock`. The scripts use that socket (through the `docker` CLI) to run Postgres
as a **sibling container on the host**, not inside Jenkins, so it survives Jenkins restarts and
upgrades.

| What | Value (`<ENV>` = `development`) |
|------|------|
| Container | `hpds-etl-participant-db-<ENV>`, image `docker.io/library/postgres:16`, `--restart=unless-stopped` |
| Data | named volume `hpds-etl-participant-db-<ENV>-data` |
| Port | host port `5432`, which runners connect to over the VPC |
| Database / role / schema | `etl_db` / `hpds_etl` / `etl` |
| Dumps | `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/` |

The version is pinned by the image. `participant-db-start` also checks the running server
itself, and replaces any server that is not PostgreSQL 16 (see [Start Rules](#start-rules)).
Nothing is installed on the Jenkins host or in the Jenkins container: jobs cannot become root
there, and anything installed in the Jenkins container would vanish when it is recreated.

Admin work (creating the role, restores, dumps) runs as the `postgres` superuser over the
container's local socket. No superuser password is stored anywhere.

---

## Lifecycle

```
orchestrator (/Jenkinsfile or /Jenkinsfile.migration)
  Build ▸ Tests ▸ (Resolve studies)
  ▸ Start participant DB ── participant-db-start
  │     is it up, on PostgreSQL 16, holding clean data?  keep it
  │     otherwise (or OVERWRITE_DB):  save the live DB ▸ recreate the container ▸ restore
  │     write host/port/dbname/username/password to the Jenkins-side credentials file
  ▸ … DB-backed runner stages (each gets the credentials in its user data) …
  post { always }
  ▸ participant-db-backup   (only if this build's start succeeded)
        pg_dump --format=d ▸ tar ▸ upload ▸ verify size and dumped-at
             SUCCESS or UNSTABLE:  overwrite participant_db.tar               (latest clean backup)
             otherwise:            overwrite participant_db_not_promoted.tar  (failed or aborted run)
        record the live database as clean / dirty in live_state.json
        the database stays up
```

| Property | How it holds |
|----------|--------------|
| **A failed run never becomes the clean backup** | Only a SUCCESS/UNSTABLE build overwrites `participant_db.tar`. A failed or aborted run's dump goes to `participant_db_not_promoted.tar`. |
| **A failed run's writes don't silently carry over** | Its backup marks the live database *dirty*. By default the next start replaces it from the clean backup (`IF_LAST_RUN_FAILED=restore-clean`); `continue` keeps building on it. |
| **Nothing is replaced unsaved** | Before a live database is replaced, it is dumped to `participant_db_pre_overwrite.tar` and the upload verified. If that fails, nothing is touched. |
| **A bad restore source changes nothing** | The source is resolved (and checked to exist) before the container is removed. |
| **No lost history** | Every dump overwrites a fixed key on a versioned bucket. Both jobs refuse to run if versioning is not `Enabled`. |

PREFLIGHT_ONLY runs start no database and take no backup.

---

## Jobs

Both live in the `hpds-etl` Jenkins folder. Both orchestrators call them, through `DB_START_JOB`
and `DB_BACKUP_JOB`.

| Jenkins job | Script path | Parameters |
|-------------|-------------|------------|
| `participant-db-start` | `etl-runners/participant-db/Jenkinsfile.start` | `OVERWRITE` (default `false`), `RESTORE_FROM`, `IF_LAST_RUN_FAILED` (`restore-clean` / `continue`), `RUN_ID`, `ENV` |
| `participant-db-backup` | `etl-runners/participant-db/Jenkinsfile.backup` | `PROMOTE_BACKUP` (default `true`), `ENV` |

The orchestrators expose the start parameters as `OVERWRITE_DB`, `DB_RESTORE_FROM` and
`IF_LAST_RUN_FAILED`.

Locally, from `etl-runners/participant-db/` on a machine with the socket and AWS access:

```bash
make start                                 # OVERWRITE=true RESTORE_FROM=… IF_LAST_RUN_FAILED=continue
make backup PROMOTE=false                  # dump to participant_db_not_promoted.tar
make status                                # is it up, with credentials saved?
make test                                  # the scripts' tests (no runtime or AWS needed)
```

---

## Start Rules

`RESTORE_FROM` can be blank (the latest clean backup, `participant_db.tar`), `none` (an empty
schema from `schema.sql`), a file under `backups/` (for example `participant_db_not_promoted.tar`
or `participant_db_pre_overwrite.tar`), or a full `s3://` URI to a tarred `pg_dump --format=d`
dump.

| The database is… | `participant-db-start` |
|---|---|
| up, on 16, clean | **keeps it**; refreshes the saved credentials. `RESTORE_FROM` is ignored without `OVERWRITE`. |
| up, and `OVERWRITE=true` | saves it to `participant_db_pre_overwrite.tar`, then **replaces it** from `RESTORE_FROM` (blank = the latest clean backup) |
| up, holding a failed run | `IF_LAST_RUN_FAILED=restore-clean` (default): saves it, then **replaces it from the latest clean backup**. `continue`: **keeps it**. |
| up, but not PostgreSQL 16 | saves it, **recreates it on 16**, and restores that save (or, if it holds a failed run and `restore-clean`, the clean backup) |
| stopped (container present) | starts it and saves it if it comes back, then **creates it** from `RESTORE_FROM`, else the latest clean backup |
| absent | **creates it** from `RESTORE_FROM`, else the latest clean backup; after a failed run with `continue`, from `participant_db_not_promoted.tar` |

If there is no `participant_db.tar` yet, a new database gets an empty schema. That's right for
a brand-new environment and **wrong** for one with history: [seed it first](#supplying-the-initial-dump).
Any S3 error other than "not found" stops the start instead of falling back to an empty schema.

A database restored from the clean backup, a chosen file or a fresh schema is recorded *clean*.
One restored from `participant_db_not_promoted.tar` because of `continue` stays *dirty*.

---

## Where the Dumps Live

```
s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/
├─ participant_db.tar                 the latest clean backup: what a start restores by default
├─ participant_db_not_promoted.tar    the most recent failed or aborted run's dump
├─ participant_db_pre_overwrite.tar   the live database, saved just before a start replaced it
└─ live_state.json                    {"state": "clean"|"dirty", reason, at, build}
```

Each key is overwritten in place, and the bucket's versioning keeps every earlier version. Every
dump carries `dumped-at` metadata (its UTC timestamp), which identifies a version. Each `.tar`
holds one directory produced by:

```bash
pg_dump --format=d --jobs=4 \
   --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
   --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
   --schema etl etl_db
```

These flags leave out everything tied to one server (owners, grants, tablespaces, publications),
so a dump restores cleanly into a fresh server under the app role.

**Older dumps.** To list them:

```bash
aws s3api list-object-versions --bucket bdc-etl-data-d0d6191 \
  --prefix avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/participant_db.tar \
  --query 'Versions[].[VersionId, LastModified, Size, IsLatest]' --output table
```

To restore an older one once, download that version, upload it under another name in
`backups/`, and run `participant-db-start` with `OVERWRITE=true RESTORE_FROM=<that name>`.

---

## Prerequisites on the Jenkins Host

One-time, by someone with access to the host and its AWS account. Jobs cannot do these.

1. **Runners must reach the host's port 5432.**
   - The Jenkins host's security group allows TCP 5432 from the runners' security group
     (`sg-0932143f21f7c533b` in `development`).
   - The host firewall allows it. The SRCE golden image's nftables drops unlisted ports:
     ```bash
     sudo nft add rule inet filter input tcp dport 5432 accept
     sudo sh -c 'nft list ruleset > /etc/nftables/nftables.rules'
     ```
2. **Runners need the host's address.** Set `participant_db_host` (the Jenkins host's private IP
   or DNS name) in `etl-runners/environments/<ENV>.tfvars`. Without it, `participant-db-start`
   asks instance metadata, which a container can reach only if the instance's metadata hop limit
   is at least 2.
3. **The container should come back after a host reboot.** `--restart=unless-stopped` needs
   podman's restart service on the host: `sudo systemctl enable podman-restart.service`. Without
   it, the next `participant-db-start` finds the container stopped and recreates it from the
   latest backup.
4. **Disk.** The volume holds the database, and each dump or restore is staged in the container
   (about twice the database size while it runs). The backup checks free space first.

The Jenkins user must also be able to use the podman socket (it already must, to build runner
images), and the instance role needs S3 access to the dumps prefix (it already has it).

---

## Switching Over from the EC2 Database

The previous design ran Postgres on a short-lived EC2 instance. Before deploying this change:

1. If an EC2 database is still running in an environment, run the **old**
   `participant-db-stop` job one last time from the previous commit, with
   `PROMOTE_BACKUP=true` if its data is good. That writes `participant_db.tar` and destroys the
   instance and its Terraform state. (Or use `PROMOTE_BACKUP=false` to keep the restore point,
   then `RESTORE_FROM=participant_db_not_promoted.tar` below.)
2. Point the `participant-db-start` job at the new `Jenkinsfile.start`, create
   `participant-db-backup` for `Jenkinsfile.backup`, and delete the `participant-db-stop` job.
3. Do the [host prerequisites](#prerequisites-on-the-jenkins-host).
4. Run `participant-db-start`. With no container yet, it creates one from `participant_db.tar`.

---

## Supplying the Initial Dump

A brand-new environment has nothing to restore. To carry existing participants (and their
integer `hpds_id`s) over from another database:

1. **Take the dump** with the flags above and `--schema etl` (the restore checks that the
   schema is called `etl`), then tar it:

   ```bash
   PGPASSWORD="…" pg_dump --host=<source-host> --port=5432 --username=<user> \
      --file=participant_db_seed --format=d --jobs=10 \
      --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
      --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
      --schema etl <source-dbname>
   tar -cvf participant_db_seed.tar participant_db_seed
   ```

   Use a `pg_dump` no newer than PostgreSQL 16.

2. **Upload it as the clean backup** (the directory inside the tar can have any name; the restore
   looks for its `toc.dat`):

   ```bash
   aws s3 cp participant_db_seed.tar \
     s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/development/backups/participant_db.tar
   ```

3. Run `participant-db-start` (with `OVERWRITE=true` if a database is already up).

The `hpds_id_seq` sequence's value travels with the dump, so new participants keep receiving ids
above the migrated ones.

---

## Credentials

`participant-db-start` generates the `hpds_etl` role's password when it creates the database and
writes the connection details, mode `600` (directory `700`), to
`${HPDS_ETL_CREDENTIALS_DIR:-$HOME/.hpds-etl}/participant-db-<ENV>.json` on the Jenkins server:

```json
{ "engine": "postgres", "host": "<participant_db_host>", "port": 5432, "dbname": "etl_db",
  "schema": "etl", "username": "hpds_etl", "password": "…", "container": "hpds-etl-participant-db-<ENV>" }
```

`etl-runners/common.mk` hands that file to each DB-backed runner as `TF_VAR_db_credentials_json`.
On every start of a kept database, the file is checked by logging in with it. If it is missing or
its password no longer works (for example after the Jenkins home was rebuilt), the role gets a new
password and the file is rewritten. The password is passed to `psql` on stdin, never on a command
line, and is never printed.

Every job that uses the file (start, backup, and every DB-backed runner job) must run on this
Jenkins node, since it is a local file. With one node, `agent any` already guarantees that.

---

## Running a DB-Backed Runner Standalone

Run `participant-db-start` (it leaves the database up), then the runner job. Each DB-backed
runner's `require-db.sh` check fails fast, before provisioning anything, if the container isn't
accepting connections or the credentials file is missing. Run `participant-db-backup` afterwards
if the runner's writes should be saved; otherwise the next pipeline run's backup captures them.

---

## Recovery and Troubleshooting

| Symptom | What to do |
|---|---|
| A start failed after "Removing hpds-etl-participant-db-…" | The database was replaced but not brought back. Its previous contents are in `participant_db_pre_overwrite.tar` (if it was up): run `participant-db-start` with `OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar`. |
| `participant-db-backup … ended FAILURE` at the end of a run | That run's writes are only in the live database. Fix the cause (versioning, disk space, S3 access) and re-run `participant-db-backup` with the same `PROMOTE_BACKUP`. |
| You want a failed run's data back after the next start replaced it | It's in `participant_db_not_promoted.tar`: `OVERWRITE=true RESTORE_FROM=participant_db_not_promoted.tar`. |
| `cannot reach the container runtime` | The podman socket is not mounted in the Jenkins container, or the Jenkins user can't open it. |
| Runners fail to connect | Check the [host prerequisites](#prerequisites-on-the-jenkins-host): security group, host nftables rule, and `participant_db_host`. |
| `versioning … is not Enabled` | Enable versioning on the bucket; dumps overwrite fixed keys. |

To inspect the live database: `docker exec -it -u postgres hpds-etl-participant-db-<ENV> psql etl_db`.

---

## Tests

`etl-runners/participant-db/test/run-tests.sh` (`make test`; also run in both orchestrators'
`Tests` stage) covers:

- the full start decision table;
- `start.sh`, `backup.sh` and `require-db.sh` end to end, against stub `docker` and `aws`
  commands that simulate the container and a versioned bucket.

The failure cases include an unversioned bucket, S3 errors, a missing backup, a failed or
unverifiable pre-overwrite dump, a failed restore, a dump without `toc.dat`, too little disk, and
the database being down.

They need only bash and jq.
