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
| Config | named volume `hpds-etl-participant-db-<ENV>-conf`, mounted read-only at `/etc/pdb`: TLS certificate and key, superuser password file, `pg_hba.conf` |
| Port | host port `5432`, which runners connect to over the VPC, **TLS only** (`ssl=on`; runners use `sslmode=require`) |
| App credentials | SSM SecureString `/hpds-etl/<ENV>/participant-db/app-credentials` (see [Credentials](#credentials)) |
| Database / role / schema | `etl_db` / `hpds_etl` / `etl` |
| Dumps | `s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/` |

The version is pinned by the image. `participant-db-start` also checks the running server
itself, and replaces any server that is not PostgreSQL 16 (see [Start Rules](#start-rules)).
Nothing is installed on the Jenkins host or in the Jenkins container: jobs cannot become root
there, and anything installed in the Jenkins container would vanish when it is recreated.

Admin work (creating the role, restores, dumps) runs as the `postgres` superuser over the
container's local socket (`docker exec -u postgres`, peer authentication).

**Access rules** (`pg_hba.conf`, which `lib.sh` rewrites in the config volume on every container
run and points the server at with `-c hba_file=…`):

| Who | How |
|-----|-----|
| `postgres` (superuser) | local socket only (`peer`). Every network login is rejected, over TLS or not. |
| `hpds_etl` (app role) | `hostssl`, database `etl_db`, `scram-sha-256`. Plain-text connections are rejected. |
| anyone else | rejected |

**TLS.** On first use, `lib.sh` generates a self-signed certificate (RSA 2048, 10 years,
CN = the container name) into the config volume as `server.crt` / `server.key`. The key is mode
`600`, owned by the image's `postgres` user. It runs `openssl` inside a one-off `postgres:16`
container, so nothing is installed on the host. The certificate is kept across replaces. Runners
use `sslmode=require`: the connection is encrypted, but the certificate is not verified. A
missing certificate or key is regenerated the next time a container is created. A kept container
is recreated (on the same data volume) only when its `ssl` or `hba_file` setting is wrong. That happens once, on the first start after upgrading from a version without TLS, and it restarts Postgres, dropping open connections (and plain-text clients cannot reconnect). Run that start when no DB-backed job is running.

**The superuser password.** The image will not initialise a data directory without one. A random
one is generated *inside* the config volume (`superuser-password`, mode `600`, owned by
`postgres`), and the image reads it through `POSTGRES_PASSWORD_FILE`. So it is not on any command
line, nor in `docker inspect` / `podman inspect`, nor in Jenkins. It is stored: in that file, and
(hashed) in the database. It is never used, because network logins as `postgres` are rejected.
Anyone who can read the volume or `docker exec` into the container is already root-equivalent on
the host. A data volume created by an earlier version keeps the password it was initialised with
(that version passed it with `-e`, so it was visible in `inspect` until the container was
recreated); it is equally unusable over the network now.

---

## Lifecycle

```
orchestrator (/Jenkinsfile or /Jenkinsfile.migration)
  Build ▸ Tests ▸ (Resolve studies)
  ▸ Start participant DB ── participant-db-start
  │     is it up, on PostgreSQL 16, holding clean data?  keep it
  │     otherwise (or OVERWRITE_DB):  save the live DB ▸ recreate the container ▸ restore
  │     give hpds_etl a new password (kept: ALTER ROLE; replaced: the new role's)
  │     publish host/port/dbname/username/password to SSM (SecureString, overwritten)
  │     record the live database dirty: "run <RUN_ID> in progress"
  ▸ … DB-backed runner stages (each runner fetches the credentials from SSM at boot) …
  post { always }
  ▸ participant-db-backup   (only if this build's start succeeded)
        pg_dump --format=d ▸ tar ▸ upload ▸ verify size and dumped-at
             SUCCESS or UNSTABLE:  overwrite participant_db.tar               (latest clean backup)
             otherwise:            overwrite participant_db_not_promoted.tar  (failed or aborted run)
        record the live database as clean (promoted) / dirty in live_state.json
        the database stays up
```

| Property | How it holds |
|----------|--------------|
| **A failed run never becomes the clean backup** | Only a SUCCESS/UNSTABLE build overwrites `participant_db.tar`. A failed or aborted run's dump goes to `participant_db_not_promoted.tar`. |
| **A failed run's writes don't silently carry over** | The start records the live database *dirty* for the run, and only the run's promoted backup records it *clean*. So a run that fails, is aborted, or never reaches its backup (killed, agent lost, backup failed) leaves it dirty. By default the next start replaces it from the clean backup (`IF_LAST_RUN_FAILED=restore-clean`); `continue` keeps building on it. |
| **Nothing is replaced unsaved** | Before a live database is replaced, it is dumped to `participant_db_pre_overwrite.tar` and the upload verified. If that fails, nothing is touched. A stopped or recovering database that doesn't come up to be saved is not removed (the start fails) unless `OVERWRITE=true`. |
| **A failed replace can be retried safely** | A replace records the live database as *replacing* before it removes anything. The next start neither keeps nor saves that partial restore, so `participant_db_pre_overwrite.tar` still holds the database it replaced. `participant-db-backup` refuses to dump it. |
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
make status                                # is it up, with working credentials published?
make test                                  # the scripts' tests (no runtime or AWS needed)
```

---

## Start Rules

`RESTORE_FROM` can be blank (the latest clean backup, `participant_db.tar`), `none` (an empty
schema from `schema.sql`), a file under `backups/` (for example `participant_db_not_promoted.tar`
or `participant_db_pre_overwrite.tar`), or a full `s3://<bucket>/<key>` URI to a tarred
`pg_dump --format=d` dump. Surrounding whitespace is trimmed. Anything with other characters than
letters, digits and `. _ / : @ + = , -` (spaces, quotes, …) is rejected before anything changes.

A restore needs free disk of about **2 × (dump + database)**: the staged dump plus the restored
database, with the same 2× margin a dump uses. Every dump this tooling makes records the
database's size (`db-bytes` metadata). For a dump without it (a hand-made seed), the database is
assumed to be `RESTORE_SIZE_FACTOR` (5) times the compressed dump. `participant-db-start` checks
this twice. The first check runs before anything is removed, counting the space the live database
will free; if it fails, nothing is changed. The second runs in the new container, before the
download; if it fails, the database is *replacing* (free space and re-run). Either way the error
says "not enough space … to restore".

| The database is… | `participant-db-start` |
|---|---|
| up, on 16, clean | **keeps it**; rotates the password (with `RUN_ID`) and republishes the credentials. A container running without TLS or the managed `pg_hba.conf` is recreated on the same data volume first (nothing is dumped or restored). `RESTORE_FROM` is ignored without `OVERWRITE`. |
| up, and `OVERWRITE=true` | saves it to `participant_db_pre_overwrite.tar`, then **replaces it** from `RESTORE_FROM` (blank = the latest clean backup) |
| up, holding a failed run | `IF_LAST_RUN_FAILED=restore-clean` (default): saves it, then **replaces it from the latest clean backup**. `continue`: **keeps it**. |
| up, but not PostgreSQL 16 | saves it, **recreates it on 16**, and restores that save (or, if it holds a failed run and `restore-clean`, the clean backup) |
| stopped, or running but not accepting connections | starts it and waits up to `RESTART_STOPPED_TIMEOUT_SECONDS` (600) for it, saves it, then **creates it** from `RESTORE_FROM`, else the latest clean backup. If it doesn't come up, **the start fails and nothing is removed**; `OVERWRITE=true` discards it instead. |
| absent, but its data volume exists | runs a container on that volume, then as stopped |
| absent | **creates it** from `RESTORE_FROM`, else the latest clean backup; after a failed run with `continue`, from `participant_db_not_promoted.tar` |
| *replacing*: the last start's replace did not finish | **replaces the partial restore without saving it**, from `RESTORE_FROM`, else from what that replace was restoring |

`RESTORE_FROM=participant_db_pre_overwrite.tar` is refused when the start would first save the live
database to that same file (nothing is changed); copy it under another name and restore that. After
a failed replace this doesn't apply, since the partial restore is not saved.

If there is no `participant_db.tar` yet, a new database gets an empty schema. That's right for
a brand-new environment and **wrong** for one with history: [seed it first](#supplying-the-initial-dump).
Any S3 error other than "not found" stops the start instead of falling back to an empty schema.

With `RUN_ID` set (the orchestrators pass `<BUILD_TAG>-db`), a start always ends by recording the
live database *dirty* ("run … in progress"), whether it kept or replaced it; that run's
`participant-db-backup` with `PROMOTE_BACKUP=true` records it *clean*. A standalone start (blank
`RUN_ID`) records a database restored from the clean backup, a chosen file or a fresh schema
*clean*, keeps one restored from `participant_db_not_promoted.tar` because of `continue` *dirty*,
and leaves a kept database's state as it was.

`live_state.json` missing means "no recorded state" (a kept database is then recorded clean). Any
other S3 error reading it, or a record with no known state, stops the start: guessing could erase a
*dirty* or *replacing* marker.

---

## Where the Dumps Live

```
s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<ENV>/backups/
├─ participant_db.tar                 the latest clean backup: what a start restores by default
├─ participant_db_not_promoted.tar    the most recent failed or aborted run's dump
├─ participant_db_pre_overwrite.tar   the live database, saved just before a start replaced it
└─ live_state.json                    {"state": "clean"|"dirty"|"replacing", reason, at, build}
                                      (+ source, next while replacing: what the retry restores)
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
   - The host firewall allows it, **from the runners' network only**. The SRCE golden image's
     nftables drops unlisted ports. Use the runner subnet's CIDR (or the VPC's), not an open rule:
     ```bash
     RUNNER_CIDR=10.x.y.0/24     # the runner subnet (or VPC) CIDR in this environment
     sudo nft add rule inet filter input ip saddr "$RUNNER_CIDR" tcp dport 5432 accept
     sudo sh -c 'nft list ruleset > /etc/nftables/nftables.rules'
     ```
     The container publishes 5432 on all host interfaces, so the security group and this rule
     are what keep it to the VPC. Even so, only the app role can log in over the network, and
     only over TLS.
2. **Runners need the host's address.** Set `participant_db_host` (the Jenkins host's private IP
   or DNS name) in `etl-runners/environments/<ENV>.tfvars`. Without it, `participant-db-start`
   asks instance metadata, which a container can reach only if the instance's metadata hop limit
   is at least 2.
3. **After a host reboot.** Podman has no daemon, so `--restart=unless-stopped` only restarts the
   container when it exits while podman is running. On boot, only `podman-restart.service` starts
   containers (`sudo systemctl enable podman-restart.service`), and only those whose restart
   policy matches its filter. Check it with `systemctl cat podman-restart.service`: many podman
   versions start only `restart-policy=always`, so this container stays stopped. Either way, the
   next `participant-db-start` finds it stopped, starts it, waits up to
   `RESTART_STOPPED_TIMEOUT_SECONDS` for crash recovery, saves it, and recreates it from the latest
   backup. If it does not come up, it fails with the volume untouched. DB-backed runner jobs run
   on their own fail at `require-db.sh` until then.
4. **Disk.** The volume holds the database, and each dump or restore is staged in the container
   (about twice the database size while it runs). The backup checks free space first, and so does
   a restore (see [Start Rules](#start-rules)).
5. **IAM for the Jenkins instance role** (the jobs run with it). See [Credentials](#credentials).

The Jenkins user must also be able to use the podman socket (it already must, to build runner
images), and the instance role needs S3 access to the dumps prefix (it already has it), plus the
SSM and KMS permissions under [Credentials](#credentials).

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

The `hpds_etl` role's connection details are published to an **SSM SecureString**, the only copy
anywhere:

```
/hpds-etl/<ENV>/participant-db/app-credentials        (etl-runners/common/db-credentials-path.sh <ENV>)
```

```json
{ "engine": "postgres", "host": "<participant_db_host>", "port": 5432, "dbname": "etl_db",
  "schema": "etl", "username": "hpds_etl", "password": "…", "container": "hpds-etl-participant-db-<ENV>" }
```

DB-backed runners fetch it at boot (instance role: `ssm:GetParameter` and `kms:Decrypt`) and
connect with `sslmode=require`. It is not in user data or Terraform state.

**When it is written.** `participant-db-start` overwrites it (`put-parameter --type SecureString
--overwrite`) on every successful start, after the password is set:

| Start | Password |
|-------|----------|
| replaces the database | new: the role is created again with a random password |
| keeps it, with `RUN_ID` (every orchestrator run) | **rotated** (`ALTER ROLE … PASSWORD`). Runners of an earlier run are finished by then (the two pipelines never run at the same time). |
| keeps it, standalone (blank `RUN_ID`) | unchanged if the published one logs in, and republished (refreshing the host). A new one only if the server **rejects** it, or nothing usable is published. A connection failure, timeout or SSM error fails the start without changing the password, so a standalone start never rotates the password under running jobs. (The one-time TLS recreation above still restarts the server.) |

If publishing fails (after retries), the start fails. The value goes to the AWS CLI through a
`600`-mode temp file (`--value file://…`), never on a command line. It reaches `psql` on stdin, and
is never printed. Each SSM call is tried up to `AWS_ATTEMPTS` (4) times with a growing pause, and a
missing parameter is not retried.

Earlier versions kept a copy in `~/.hpds-etl/participant-db-<ENV>.json` on the Jenkins server.
`participant-db-start` deletes it.

**Required IAM for the Jenkins instance role**, on
`arn:aws:ssm:<region>:<account>:parameter/hpds-etl/<ENV>/participant-db/app-credentials`:

| Action | Used by |
|--------|---------|
| `ssm:PutParameter` | `participant-db-start` (publish) |
| `ssm:GetParameter` | `participant-db-start` (check a kept database's password), `require-db.sh` (every DB-backed runner job) |
| `kms:Encrypt`, `kms:Decrypt` (and `kms:GenerateDataKey`) | on the parameter's key: the AWS-managed `alias/aws/ssm` by default (allowed through SSM for principals with the SSM permissions above, via `kms:ViaService = ssm.<region>.amazonaws.com`), or the key set as `SSM_KMS_KEY_ID` in `participant-db.conf`, whose key policy must then allow the role |

Example policy statement:

```json
{ "Effect": "Allow",
  "Action": ["ssm:PutParameter", "ssm:GetParameter"],
  "Resource": "arn:aws:ssm:us-east-1:<account>:parameter/hpds-etl/development/participant-db/app-credentials" },
{ "Effect": "Allow",
  "Action": ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"],
  "Resource": "<arn of the key: aws/ssm's key ARN, or SSM_KMS_KEY_ID>",
  "Condition": { "StringEquals": { "kms:ViaService": "ssm.us-east-1.amazonaws.com" } } }
```

---

## Running a DB-Backed Runner Standalone

Run `participant-db-start` (it leaves the database up), then the runner job. Each DB-backed
runner's `require-db.sh` check fails fast, before provisioning anything, if the container isn't
accepting connections, nothing usable is published in SSM, or the published credentials don't log
in (for example after a failed replace). Run `participant-db-backup` afterwards
if the runner's writes should be saved; otherwise the next pipeline run's backup captures them.

---

## Recovery and Troubleshooting

| Symptom | What to do |
|---|---|
| A start failed after "Removing hpds-etl-participant-db-…" | The database was replaced but not brought back, and is recorded *replacing*. Re-run `participant-db-start` to retry the same restore. To get the replaced database back instead, run it with `OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar` (if it was saved); the partial restore is not saved over that file. |
| A start failed with "did not accept connections … Nothing was removed" | The container is stopped or still recovering (for example after a host reboot), and its data is still in its volume. Check `docker logs hpds-etl-participant-db-<ENV>` and re-run once it accepts connections. To discard that data and restore a backup, re-run with `OVERWRITE=true` (and `RESTORE_FROM`). |
| A start failed with "cannot read …/live_state.json" or "cannot check …" | S3 access or credentials problem; nothing was changed. Fix it and re-run. |
| `participant-db-backup … ended FAILURE` at the end of a run | That run's writes are only in the live database. Fix the cause (versioning, disk space, S3 access) and re-run `participant-db-backup` with the same `PROMOTE_BACKUP`. |
| You want a failed run's data back after the next start replaced it | It's in `participant_db_not_promoted.tar`: `OVERWRITE=true RESTORE_FROM=participant_db_not_promoted.tar`. |
| `cannot reach the container runtime` | The podman socket is not mounted in the Jenkins container, or the Jenkins user can't open it. |
| Runners fail to connect | Check the [host prerequisites](#prerequisites-on-the-jenkins-host): security group, host nftables rule, and `participant_db_host`. Clients must use TLS (`sslmode=require`); a plain-text connection gets "no pg_hba.conf entry … no encryption". |
| A start failed with "could not check the published credentials … The password was not changed" | The database didn't answer the login check (not a rejected password). Check `docker logs` and re-run. |
| A start failed with "could not publish the connection details to SSM" or "cannot read SSM parameter" | SSM or KMS permissions (see [Credentials](#credentials)) or throttling. A kept database's password may already be rotated; re-running the start publishes a fresh one. |
| A start failed with "not enough space … to restore" | Free disk on the Jenkins host and re-run `participant-db-start` (the database is *replacing*, so it retries the restore). |
| A start failed with "could not prepare the TLS certificate …" | The one-off `postgres:16` setup container failed (volume, image, or `openssl` missing from the image). See the log above the error. |
| `versioning … is not Enabled` | Enable versioning on the bucket; dumps overwrite fixed keys. |

To inspect the live database: `docker exec -it -u postgres hpds-etl-participant-db-<ENV> psql etl_db`.

---

## Tests

`etl-runners/participant-db/test/run-tests.sh` (`make test`; also run in both orchestrators'
`Tests` stage) covers:

- the full start decision table;
- `start.sh`, `backup.sh` and `require-db.sh` end to end, against stub `docker` and `aws`
  commands that simulate the container and a versioned bucket.

They also cover password rotation per run (and none on a connection failure or SSM error), the
SSM publish (contract, retries, failure fails the start), TLS and pg_hba arguments, the superuser
password not being on the command line, recreating a pre-TLS container on its volume, the restore
free-space check, and `RESTORE_FROM` trimming and validation.

The failure cases include an unversioned bucket, S3 errors (including reading `live_state.json`),
a missing backup, a failed or unverifiable pre-overwrite dump, a failed restore and its recovery
(the pre-overwrite dump is not overwritten), a dump without `toc.dat`, too little disk, a database
that is down or won't come back (its volume is kept), an orphaned data volume, and a run that
never reaches its backup (the database stays dirty).

They need only bash and jq.
