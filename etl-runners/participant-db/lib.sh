#!/bin/bash
#
# The participant database: a long-lived Postgres container on the Jenkins host, reached through
# the `docker` CLI (in this Jenkins, the host's podman socket mounted into the Jenkins container).
# Sourced by start.sh, backup.sh and ../common/require-db.sh; holds no state of its own.
#
#   container  hpds-etl-participant-db-<ENV>, image $PG_IMAGE, port $DB_PORT published on the host
#   data       named volume hpds-etl-participant-db-<ENV>-data (survives Jenkins restarts)
#   dumps      s3://$BACKUP_BUCKET/$BACKUP_PREFIX/<ENV>/backups/   (versioned bucket)
#                participant_db.tar                the clean restore point (successful runs)
#                participant_db_not_promoted.tar   the last failed or aborted run
#                participant_db_pre_overwrite.tar  the live DB, saved just before start replaced it
#                live_state.json                   what the live DB holds: clean | dirty (a failed run,
#                                                  or one still in progress) | replacing (a start was
#                                                  replacing it and did not finish)
#   config     volume hpds-etl-participant-db-<ENV>-conf at $PDB_CONF_DIR (kept across replaces):
#                server.crt, server.key   self-signed TLS certificate, made on first use (ssl=on)
#                superuser-password       random, made on first use. initdb reads it only on a
#                                         new volume, through POSTGRES_PASSWORD_FILE
#                pg_hba.conf              rewritten on every container run: the superuser only
#                                         over the local socket, the app role only over TLS
#   creds      SSM SecureString ../common/db-credentials-path.sh <ENV>: what DB-backed runners
#              connect with, published by start.sh
#
# Admin work runs as the postgres superuser over the container's local socket (peer auth). The
# superuser password is never used: pg_hba rejects superuser logins over the network. The app
# role's password is generated here. It reaches psql on stdin and SSM through a 600-mode temp
# file, never a command line.
#
# xtrace must stay off in anything sourcing this: it handles the app password.

PDB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=participant-db.conf
source "$PDB_DIR/participant-db.conf"
DOCKER="${DOCKER:-docker}"

pdb_log() { echo "[$(date -u +%H:%M:%S)] [participant-db] $*"; }
# PDB_ERROR_FILE (set by the Jenkinsfiles) also gets the message on one line, for the build
# description.
pdb_die() {
    pdb_log "ERROR: $*" >&2
    local msg="$*"
    echo "${msg//$'\n'/ }" >> "${PDB_ERROR_FILE:-/dev/null}"
    exit 1
}

# Sets every per-environment name. Call first.
pdb_init() {
    ENV_NAME="${1:?pdb_init <env>}"
    CONTAINER="hpds-etl-participant-db-$ENV_NAME"
    VOLUME="$CONTAINER-data"
    CONF_VOLUME="$CONTAINER-conf"
    BACKUP_KEY_PREFIX="$BACKUP_PREFIX/$ENV_NAME/backups"
    BACKUP_URI="s3://$BACKUP_BUCKET/$BACKUP_KEY_PREFIX"
    CREDS_PARAM="$("$PDB_DIR/../common/db-credentials-path.sh" "$ENV_NAME")"
    # PDB_ENV_DIR: tests point this at their own environment files.
    local tfvars="${PDB_ENV_DIR:-$PDB_DIR/../environments}/$ENV_NAME.tfvars"
    [[ -f "$tfvars" ]] || pdb_die "no environment file $tfvars"
    REGION="${AWS_REGION:-$(pdb_tfvar aws_region "$tfvars")}"
    DB_HOST_CONFIGURED="$(pdb_tfvar participant_db_host "$tfvars")"
}

pdb_tfvar() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\(.*\)\"/\1/p" "$2"; }

# --------------------------------------------------------------------------- container

pdb_container_exists() { "$DOCKER" container inspect "$CONTAINER" >/dev/null 2>&1; }
pdb_volume_named_exists() { "$DOCKER" volume inspect "$1" >/dev/null 2>&1; }
pdb_volume_exists() { pdb_volume_named_exists "$VOLUME"; }

pdb_container_running() {
    [[ "$("$DOCKER" container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == "true" ]]
}

# Accepting connections over TCP. The image's init phase runs a temporary server on the local
# socket only, so TCP readiness means initialisation is finished.
pdb_accepting() {
    "$DOCKER" exec "$CONTAINER" pg_isready -q -h 127.0.0.1 -p 5432 >/dev/null 2>&1
}

# up | down (container present but stopped or not answering) | absent
pdb_state() {
    if ! pdb_container_exists; then
        echo absent
    elif pdb_container_running && pdb_accepting; then
        echo up
    else
        echo down
    fi
}

# psql as the superuser over the local socket. SQL on stdin or -c.
pdb_psql() {
    local db="${PDB_PSQL_DB:-postgres}"
    "$DOCKER" exec -i -u postgres "$CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -d "$db" "$@"
}

pdb_server_major() {
    local num
    num=$(pdb_psql -tAc "SHOW server_version_num" | tr -d '[:space:]') || return 1
    [[ "$num" =~ ^[0-9]+$ ]] || return 1
    echo $((num / 10000))
}

# Waits up to <timeout> seconds for the server to accept connections. Returns 1 if it doesn't.
pdb_wait_accepting() {
    local timeout="$1" waited=0
    until pdb_accepting; do
        (( waited >= timeout )) && return 1
        sleep 2; waited=$((waited + 2))
    done
}

pdb_wait_ready() {
    local timeout="${1:-$READY_TIMEOUT_SECONDS}" start
    start=$(date +%s)
    until pdb_accepting; do
        if (( $(date +%s) - start >= timeout )); then
            "$DOCKER" logs --tail 40 "$CONTAINER" 2>&1 | sed 's/^/    /' >&2 || true
            pdb_die "$CONTAINER did not accept connections within ${timeout}s (container log above)"
        fi
        sleep 2
    done
}

# A brand-new container on a brand-new volume.
pdb_create_container() {
    pdb_log "Creating $CONTAINER ($PG_IMAGE, volume $VOLUME, port $DB_PORT)"
    "$DOCKER" volume create "$VOLUME" >/dev/null || pdb_die "could not create volume $VOLUME"
    pdb_run_container
    pdb_wait_ready
}

# Starts $CONTAINER on $VOLUME, without waiting for it. On a volume that already holds a data
# directory the image skips initialisation and serves that data.
pdb_run_container() {
    "$DOCKER" pull -q "$PG_IMAGE" >/dev/null || pdb_die "could not pull $PG_IMAGE"
    pdb_prepare_config
    # The image refuses to initialise without a superuser password. It reads it from a file in
    # the config volume, so it is not in the container's environment (docker/podman inspect).
    # pg_hba rejects superuser network logins, so the password is never used.
    "$DOCKER" run -d --name "$CONTAINER" --restart=unless-stopped \
        -p "$DB_PORT:5432" \
        -v "$VOLUME:/var/lib/postgresql/data" \
        -v "$CONF_VOLUME:$PDB_CONF_DIR:ro" \
        -e "POSTGRES_PASSWORD_FILE=$PDB_CONF_DIR/superuser-password" \
        "$PG_IMAGE" \
        -c "max_connections=$MAX_CONNECTIONS" -c "shared_buffers=$SHARED_BUFFERS" \
        -c password_encryption=scram-sha-256 \
        -c ssl=on -c "ssl_cert_file=$PDB_CONF_DIR/server.crt" -c "ssl_key_file=$PDB_CONF_DIR/server.key" \
        -c "hba_file=$PDB_CONF_DIR/pg_hba.conf" >/dev/null \
        || pdb_die "could not start $CONTAINER"
}

# Who may log in, and how. The superuser only over the container's local socket (peer: the OS user
# docker exec -u postgres runs as). The app role only over TLS with a password. Everything else is
# rejected.
pdb_hba() {
    cat <<HBA
# Written by etl-runners/participant-db/lib.sh on every container run; edits are overwritten.
# TYPE   DATABASE   USER          ADDRESS     METHOD
local    all        postgres                  peer
host     all        postgres      0.0.0.0/0   reject
host     all        postgres      ::/0        reject
hostssl  $DB_NAME     $DB_USERNAME      0.0.0.0/0   scram-sha-256
hostssl  $DB_NAME     $DB_USERNAME      ::/0        scram-sha-256
HBA
}

# Fills the config volume: the TLS certificate and key and the superuser password file (each made
# only if missing), and pg_hba.conf (always rewritten). This runs in a one-off container of
# $PG_IMAGE (which has openssl), so nothing is installed on the host and the secrets never pass
# through Jenkins. Files are owned by the image's postgres user; the key and password are mode 600.
pdb_prepare_config() {
    pdb_volume_named_exists "$CONF_VOLUME" || "$DOCKER" volume create "$CONF_VOLUME" >/dev/null \
        || pdb_die "could not create volume $CONF_VOLUME"
    local script='set -eu
cd "$1"
umask 077
if [ ! -s server.key ] || [ ! -s server.crt ]; then
    command -v openssl >/dev/null || { echo "openssl is not in the image" >&2; exit 1; }
    openssl req -new -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$2" \
        -keyout server.key.tmp -out server.crt.tmp 2>/dev/null
    mv server.crt.tmp server.crt
    mv server.key.tmp server.key
fi
if [ ! -s superuser-password ]; then
    head -c 24 /dev/urandom | od -An -tx1 | tr -d " \n" > superuser-password.tmp
    mv superuser-password.tmp superuser-password
fi
cat > pg_hba.conf.tmp
mv pg_hba.conf.tmp pg_hba.conf
chown postgres:postgres server.key server.crt superuser-password pg_hba.conf
chmod 600 server.key superuser-password
chmod 644 server.crt pg_hba.conf'
    pdb_hba | "$DOCKER" run --rm -i --entrypoint sh -v "$CONF_VOLUME:$PDB_CONF_DIR" "$PG_IMAGE" \
        -c "$script" sh "$PDB_CONF_DIR" "$CONTAINER" \
        || pdb_die "could not prepare the TLS certificate, superuser password file and pg_hba.conf in volume $CONF_VOLUME"
}

# A kept container must run with TLS and the managed pg_hba.conf. One created before those (or
# changed by hand) is recreated on the same data volume. Its data stays where it is; nothing is
# dumped or restored.
pdb_require_current_config() {
    local cfg want="on $PDB_CONF_DIR/pg_hba.conf"
    cfg=$(pdb_psql -tAc "SELECT current_setting('ssl') || ' ' || current_setting('hba_file')" | tr -d '\r') \
        || pdb_die "could not read the TLS and pg_hba settings of $CONTAINER"
    [[ "$cfg" == "$want" ]] && return 0
    pdb_log "$CONTAINER runs with ssl/hba_file '$cfg', not '$want': recreating the container on volume $VOLUME (its data is kept)"
    "$DOCKER" stop -t 120 "$CONTAINER" >/dev/null 2>&1 || true
    "$DOCKER" rm -f "$CONTAINER" >/dev/null 2>&1 || true
    pdb_container_exists && pdb_die "could not remove $CONTAINER to recreate it; its data is still in volume $VOLUME"
    pdb_run_container
    pdb_wait_ready
    cfg=$(pdb_psql -tAc "SELECT current_setting('ssl') || ' ' || current_setting('hba_file')" | tr -d '\r') || cfg=""
    [[ "$cfg" == "$want" ]] || pdb_die "$CONTAINER was recreated but runs with ssl/hba_file '$cfg', not '$want'"
}

# Removes the container and its data volume. Only start.sh's replace calls this, and only once the
# data is saved in a verified dump, is a partial restore (live state "replacing"), or OVERWRITE=true
# explicitly discarded it. The config volume (certificate, pg_hba.conf) is kept.
pdb_remove_container() {
    pdb_log "Removing $CONTAINER and volume $VOLUME"
    "$DOCKER" rm -f "$CONTAINER" >/dev/null 2>&1 || true
    "$DOCKER" volume rm -f "$VOLUME" >/dev/null 2>&1 || true
    if pdb_container_exists; then
        pdb_die "could not remove $CONTAINER"
    fi
}

pdb_random_hex() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# Free KB in the container's filesystem at /tmp, where dumps and restores are staged.
pdb_free_kb() {
    "$DOCKER" exec "$CONTAINER" df -Pk /tmp | awk 'NR == 2 {print $4}'
}

# --------------------------------------------------------------------------- S3

pdb_require_versioning() {
    local status
    status=$(aws s3api get-bucket-versioning --bucket "$BACKUP_BUCKET" --region "$REGION" \
               --query Status --output text 2>&1)
    [[ "$status" == "Enabled" ]] || pdb_die "versioning on s3://$BACKUP_BUCKET is '$status', not Enabled. Dumps overwrite fixed keys; without versioning the previous dump would be lost."
}

# present | absent for a key under the backups prefix. Any error other than 404 is fatal: it must
# not quietly turn into "no dump, start empty".
pdb_backup_exists() {
    local key="$BACKUP_KEY_PREFIX/$1" err
    if err=$(aws s3api head-object --bucket "$BACKUP_BUCKET" --key "$key" --region "$REGION" 2>&1 >/dev/null); then
        echo present
    elif [[ "$err" == *"Not Found"* || "$err" == *"404"* || "$err" == *"NoSuchKey"* ]]; then
        echo absent
    else
        pdb_die "cannot check s3://$BACKUP_BUCKET/$key: $err"
    fi
}

# The live_state.json record, as compact JSON: what the live database holds, as recorded by the
# last start or backup. {"state":"unknown"} when there is none (404). Any other S3 error, or a
# record with no known state, is fatal: guessing would erase a dirty or replacing marker.
# Call as rec=$(pdb_read_live_record) || exit 1.
pdb_read_live_record() {
    local found json err state
    found=$(pdb_backup_exists live_state.json) || exit 1
    if [[ "$found" == absent ]]; then
        echo '{"state":"unknown"}'
        return
    fi
    err=$(mktemp)
    if ! json=$(aws s3 cp "$BACKUP_URI/live_state.json" - --region "$REGION" 2>"$err"); then
        local msg; msg=$(cat "$err"); rm -f "$err"
        pdb_die "cannot read $BACKUP_URI/live_state.json: $msg"
    fi
    rm -f "$err"
    state=$(jq -er '.state' <<<"$json" 2>/dev/null) || state=""
    case "$state" in
        clean|dirty|replacing) jq -c . <<<"$json" ;;
        *) pdb_die "$BACKUP_URI/live_state.json records no known state (clean, dirty or replacing); fix or delete it (deleting it means the live database has no recorded state and is kept as clean)" ;;
    esac
}

# <state> <reason> [<source uri> <next state>]: the last two only for "replacing", so an unfinished
# replace can be retried.
pdb_write_live_state() {
    local state="$1" reason="$2" source="${3:-}" next="${4:-}"
    jq -n --arg state "$state" --arg reason "$reason" --arg env "$ENV_NAME" \
          --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg build "${BUILD_TAG:-manual}" \
          --arg source "$source" --arg next "$next" \
          '{state: $state, reason: $reason, env: $env, at: $at, build: $build}
           + (if $source == "" then {} else {source: $source, next: $next} end)' \
        | aws s3 cp - "$BACKUP_URI/live_state.json" --region "$REGION" --no-progress >/dev/null \
        || pdb_die "could not write $BACKUP_URI/live_state.json"
    pdb_log "Live database recorded as $state ($reason)"
}

# --------------------------------------------------------------------------- dump / restore

# Dumps the schema to <name>.tar under the backups prefix and verifies the upload.
# Flags follow the team's standard dump: object definitions and data only, nothing tied to this
# server, so it restores cleanly into a fresh server as any role.
pdb_dump_to_s3() {
    local name="$1" ts bytes db_bytes free_kb remote remote_at
    ts=$(date -u '+%Y-%m-%dT%H%M%SZ')
    db_bytes=$(pdb_psql -tAc "SELECT pg_database_size('$DB_NAME')" | tr -d '[:space:]')
    free_kb=$(pdb_free_kb)
    # The directory dump plus its tar; dumps compress, so twice the database is a safe bound.
    if [[ "$db_bytes" =~ ^[0-9]+$ && "$free_kb" =~ ^[0-9]+$ ]] && (( free_kb * 1024 < db_bytes * 2 )); then
        pdb_die "not enough space in $CONTAINER to stage the dump: $((free_kb / 1024)) MB free, database is $((db_bytes / 1048576)) MB"
    fi

    pdb_log "pg_dump $DB_NAME (schema $DB_SCHEMA) -> $name.tar"
    "$DOCKER" exec -u postgres "$CONTAINER" sh -c "rm -rf /tmp/pdb-dump && mkdir -p /tmp/pdb-dump" \
        || pdb_die "could not prepare the dump directory"
    "$DOCKER" exec -u postgres "$CONTAINER" pg_dump \
        --file="/tmp/pdb-dump/$name" --format=d --jobs="$DUMP_JOBS" \
        --no-owner --no-privileges --no-tablespaces --no-unlogged-table-data --no-comments \
        --no-publications --no-subscriptions --no-security-labels --no-toast-compression --no-table-access-method \
        --schema "$DB_SCHEMA" "$DB_NAME" \
        || pdb_die "pg_dump failed"
    "$DOCKER" exec -u postgres "$CONTAINER" tar -cf "/tmp/pdb-dump/$name.tar" -C /tmp/pdb-dump "$name" \
        || pdb_die "could not tar the dump"
    bytes=$("$DOCKER" exec "$CONTAINER" stat -c %s "/tmp/pdb-dump/$name.tar" | tr -d '[:space:]')
    [[ "$bytes" =~ ^[0-9]+$ ]] || pdb_die "could not size the dump"

    pdb_log "Uploading $BACKUP_URI/$name.tar ($bytes bytes, dumped-at $ts)"
    # db-bytes: the database's size when dumped, so a restore can check it has room for it.
    local meta="dumped-at=$ts"
    [[ "$db_bytes" =~ ^[0-9]+$ ]] && meta+=",db-bytes=$db_bytes"
    set -o pipefail
    "$DOCKER" exec "$CONTAINER" cat "/tmp/pdb-dump/$name.tar" \
        | aws s3 cp - "$BACKUP_URI/$name.tar" --region "$REGION" --no-progress \
            --expected-size "$bytes" --metadata "$meta" \
        || pdb_die "upload of $name.tar failed"
    "$DOCKER" exec -u postgres "$CONTAINER" rm -rf /tmp/pdb-dump || true

    # Size and dumped-at both match: the current version is this dump, not an earlier one.
    read -r remote remote_at < <(aws s3api head-object --bucket "$BACKUP_BUCKET" --key "$BACKUP_KEY_PREFIX/$name.tar" \
        --region "$REGION" --query '[ContentLength, Metadata."dumped-at"]' --output text 2>/dev/null || echo missing missing)
    if [[ "$remote" != "$bytes" || "$remote_at" != "$ts" ]]; then
        pdb_die "$BACKUP_URI/$name.tar is $remote bytes dumped-at $remote_at; expected $bytes bytes dumped-at $ts"
    fi
    pdb_log "Verified $BACKUP_URI/$name.tar ($bytes bytes, dumped-at $ts)"
}

# The URI a restore source names: clean | not_promoted | pre_overwrite | none | <file under
# backups/> | s3://... Prints "none" for a fresh schema (and for "clean" when no clean dump exists
# yet). Exits non-zero when a named backup is missing or S3 can't be checked, so call it as
# uri=$(pdb_source_uri ...) || exit 1.
pdb_source_uri() {
    case "$1" in
        none) echo none ;;
        clean)
            local found
            found=$(pdb_backup_exists participant_db.tar) || exit 1
            if [[ "$found" == present ]]; then
                echo "$BACKUP_URI/participant_db.tar"
            else
                echo none
            fi
            ;;
        not_promoted) pdb_require_backup participant_db_not_promoted.tar ;;
        pre_overwrite) pdb_require_backup participant_db_pre_overwrite.tar ;;
        s3://*) echo "$1" ;;
        *) pdb_require_backup "$1" ;;
    esac
}

pdb_require_backup() {
    local found
    found=$(pdb_backup_exists "$1") || exit 1
    [[ "$found" == present ]] || pdb_die "no backup $BACKUP_URI/$1"
    echo "$BACKUP_URI/$1"
}

# The free disk, in bytes, a restore of <uri> needs (0 for none): the unpacked dump staged in /tmp
# plus the restored database, with the same 2x margin as a dump. The database's size comes from
# the dump's db-bytes metadata (written by pdb_dump_to_s3). A dump without it (a hand-made seed)
# is assumed to restore to RESTORE_SIZE_FACTOR x its size, because pg_dump --format=d compresses
# and indexes are rebuilt. Dies if the dump can't be checked. Call it before anything is removed:
# need=$(pdb_restore_bytes_needed "$uri") || exit 1.
pdb_restore_bytes_needed() {
    local uri="$1" rest bucket key out tar db
    if [[ "$uri" == none ]]; then echo 0; return; fi
    rest="${uri#s3://}"; bucket="${rest%%/*}"; key="${rest#*/}"
    [[ "$uri" == s3://* && -n "$bucket" && -n "$key" && "$key" != "$rest" ]] || pdb_die "not an s3://<bucket>/<key> URI: $uri"
    out=$(aws s3api head-object --bucket "$bucket" --key "$key" --region "$REGION" \
            --query '[ContentLength, Metadata."db-bytes"]' --output text 2>&1) || pdb_die "cannot check $uri: $out"
    read -r tar db <<<"$out"
    [[ "$tar" =~ ^[0-9]+$ ]] || pdb_die "cannot tell the size of $uri (got '$out')"
    if [[ "$db" =~ ^[0-9]+$ ]]; then
        echo $(( 2 * (tar + db) ))
    else
        echo $(( tar + tar * RESTORE_SIZE_FACTOR ))
    fi
}

# Dies, before the download, unless the container has <need> bytes free for restoring <uri>.
# <reclaimed>: bytes that removing the live database will free (only for the check made before
# anything is removed).
pdb_require_restore_space() {
    local uri="$1" need="$2" reclaimed="${3:-0}" free_kb
    free_kb=$(pdb_free_kb) || free_kb=""
    if [[ ! "$free_kb" =~ ^[0-9]+$ ]]; then
        pdb_log "WARNING: could not read the free space in $CONTAINER; restoring $uri without checking it"
        return 0
    fi
    if (( free_kb * 1024 + reclaimed < need )); then
        local when="" outcome=""
        if [[ -n "${3+set}" ]]; then
            when=" once the live database is removed"
            outcome=" Nothing was changed."
        fi
        pdb_die "not enough space in $CONTAINER to restore $uri: $(( (free_kb * 1024 + reclaimed) / 1048576 )) MB free$when, and the restore needs about $((need / 1048576)) MB (the staged dump plus the restored database, twice over).$outcome Free disk space on the Jenkins host and re-run participant-db-start."
    fi
}

# Creates the app role (with a new password), its database, and then either restores the dump at
# <uri> (needing <need> bytes free) or creates the schema from schema.sql. Prints nothing; sets
# DB_PASSWORD.
pdb_load() {
    local uri="$1" need="${2:-0}" toc
    [[ "$uri" == none ]] || pdb_require_restore_space "$uri" "$need"
    DB_PASSWORD=$(pdb_random_hex)
    pdb_psql <<SQL || pdb_die "could not create role $DB_USERNAME and database $DB_NAME"
CREATE ROLE "$DB_USERNAME" LOGIN PASSWORD '$DB_PASSWORD';
CREATE DATABASE "$DB_NAME" OWNER "$DB_USERNAME";
SQL

    if [[ "$uri" == none ]]; then
        pdb_log "Creating a fresh schema from schema.sql"
        { echo "SET ROLE \"$DB_USERNAME\"; CREATE SCHEMA \"$DB_SCHEMA\"; SET search_path TO \"$DB_SCHEMA\";"
          cat "$PDB_DIR/../../src/main/resources/repository/schema.sql"; } \
            | PDB_PSQL_DB="$DB_NAME" pdb_psql || pdb_die "schema.sql failed"
    else
        pdb_log "Restoring $uri"
        set -o pipefail
        "$DOCKER" exec -u postgres "$CONTAINER" sh -c "rm -rf /tmp/pdb-restore && mkdir -p /tmp/pdb-restore" \
            || pdb_die "could not prepare the restore directory"
        aws s3 cp "$uri" - --region "$REGION" --no-progress \
            | "$DOCKER" exec -i -u postgres "$CONTAINER" tar -xf - -C /tmp/pdb-restore \
            || pdb_die "could not download and unpack $uri"
        # A tarred pg_dump --format=d directory: whatever it is called, it holds toc.dat.
        toc=$("$DOCKER" exec "$CONTAINER" find /tmp/pdb-restore -name toc.dat -print -quit | tr -d '\r')
        [[ -n "$toc" ]] || pdb_die "$uri holds no toc.dat; expected a tar of a 'pg_dump --format=d' directory"
        # As the superuser acting as the app role: objects end up owned by it, as with --no-owner.
        "$DOCKER" exec -u postgres "$CONTAINER" pg_restore --dbname="$DB_NAME" --role="$DB_USERNAME" \
            --format=d --jobs="$DUMP_JOBS" --no-owner --no-privileges --exit-on-error "$(dirname "$toc")" \
            || pdb_die "pg_restore of $uri failed"
        "$DOCKER" exec -u postgres "$CONTAINER" rm -rf /tmp/pdb-restore || true
        local has_schema
        has_schema=$(PDB_PSQL_DB="$DB_NAME" pdb_psql -tAc \
            "SELECT count(*) FROM information_schema.schemata WHERE schema_name = '$DB_SCHEMA'" | tr -d '[:space:]')
        [[ "$has_schema" == 1 ]] || pdb_die "the restored dump has no '$DB_SCHEMA' schema (was it taken with --schema $DB_SCHEMA?)"
    fi
    # A dump taken before ix_consents_study_id was added to schema.sql lacks it, and every study
    # load's consents purge would then scan the whole table.
    PDB_PSQL_DB="$DB_NAME" pdb_psql -c "SET ROLE \"$DB_USERNAME\";
        CREATE INDEX IF NOT EXISTS ix_consents_study_id ON \"$DB_SCHEMA\".consents (study_id)" \
        || pdb_die "could not create ix_consents_study_id"
    # Unqualified names resolve to the schema for the app role (the JAR also sets DB_SCHEMA).
    pdb_psql -c "ALTER ROLE \"$DB_USERNAME\" SET search_path TO \"$DB_SCHEMA\"" || pdb_die "could not set search_path"
    pdb_log "Row counts: $(PDB_PSQL_DB="$DB_NAME" pdb_psql -tA -F ' ' -c \
        "SELECT 'participants=' || (SELECT count(*) FROM \"$DB_SCHEMA\".participants),
                'consents='     || (SELECT count(*) FROM \"$DB_SCHEMA\".consents),
                'samples='      || (SELECT count(*) FROM \"$DB_SCHEMA\".samples)")"
}

# --------------------------------------------------------------------------- credentials

# The address runners connect to: participant_db_host in the environment file, else the EC2
# instance's private IP from instance metadata.
pdb_host() {
    if [[ -n "$DB_HOST_CONFIGURED" ]]; then
        echo "$DB_HOST_CONFIGURED"
        return
    fi
    local token ip
    token=$(curl -sf -m 5 -X PUT "http://169.254.169.254/latest/api/token" \
              -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) || true
    ip=$(curl -sf -m 5 ${token:+-H "X-aws-ec2-metadata-token: $token"} \
           "http://169.254.169.254/latest/meta-data/local-ipv4" 2>/dev/null) || true
    [[ -n "$ip" ]] || pdb_die "cannot tell runners where the database is: set participant_db_host (the Jenkins host's private IP or DNS name) in environments/$ENV_NAME.tfvars"
    echo "$ip"
}

# Runs `aws "$@"` up to $AWS_ATTEMPTS times. Not in a subshell: sets PDB_AWS_OUT (stdout of the
# attempt that succeeded) and PDB_AWS_ERR (stderr of the last failure). A missing SSM parameter is
# an answer, not a transient error, so it is not retried.
pdb_aws_retry() {
    local attempt=1 errf
    errf=$(mktemp)
    PDB_AWS_OUT="" PDB_AWS_ERR=""
    until PDB_AWS_OUT=$(aws "$@" 2>"$errf"); do
        PDB_AWS_ERR=$(cat "$errf")
        if [[ "$PDB_AWS_ERR" == *ParameterNotFound* ]] || (( attempt >= AWS_ATTEMPTS )); then
            rm -f "$errf"
            return 1
        fi
        pdb_log "aws $1 $2 failed (attempt $attempt of $AWS_ATTEMPTS), retrying: ${PDB_AWS_ERR//$'\n'/ }" >&2
        sleep $(( ${AWS_RETRY_SLEEP:-$AWS_RETRY_SLEEP_SECONDS} * attempt ))
        attempt=$((attempt + 1))
    done
    rm -f "$errf"
    PDB_AWS_ERR=""
}

# Publishes the connection details, with $DB_PASSWORD, to the SSM SecureString $CREDS_PARAM
# (overwriting it), where DB-backed runners fetch them at boot. The value goes through a 600-mode
# temp file, never the command line.
pdb_publish_credentials() {
    local host tmp rc=0
    host=$(pdb_host) || exit 1
    tmp=$(umask 077; mktemp)
    if ! jq -n --arg host "$host" --argjson port "$DB_PORT" --arg db "$DB_NAME" --arg schema "$DB_SCHEMA" \
          --arg user "$DB_USERNAME" --arg pw "$DB_PASSWORD" --arg container "$CONTAINER" \
          '{engine: "postgres", host: $host, port: $port, dbname: $db, schema: $schema,
            username: $user, password: $pw, container: $container}' > "$tmp"; then
        rm -f "$tmp"
        pdb_die "could not build the connection details"
    fi
    pdb_aws_retry ssm put-parameter --region "$REGION" --name "$CREDS_PARAM" \
        --type SecureString --overwrite ${SSM_KMS_KEY_ID:+--key-id "$SSM_KMS_KEY_ID"} \
        --value "file://$tmp" --query Version --output text || rc=$?
    rm -f "$tmp"
    (( rc == 0 )) || pdb_die "could not publish the connection details to SSM parameter $CREDS_PARAM: $PDB_AWS_ERR"
    pdb_log "Published connection details to SSM $CREDS_PARAM (version $PDB_AWS_OUT, host $host:$DB_PORT)"
}

# Prints the published connection details (JSON). Returns 2 when none are published, or what is
# published is not usable. Dies on any other error. Call as json=$(pdb_published_credentials), with
# its status checked.
pdb_published_credentials() {
    if ! pdb_aws_retry ssm get-parameter --region "$REGION" --name "$CREDS_PARAM" --with-decryption \
            --query Parameter.Value --output text; then
        [[ "$PDB_AWS_ERR" == *ParameterNotFound* ]] && return 2
        pdb_die "cannot read SSM parameter $CREDS_PARAM: $PDB_AWS_ERR"
    fi
    jq -e '.host and .password' <<<"$PDB_AWS_OUT" >/dev/null 2>&1 || return 2
    printf '%s\n' "$PDB_AWS_OUT"
}

# Checks <password> as the app role, over TCP and TLS as runners connect (password on stdin).
# Prints ok, auth (the server rejected the password), or "error: <psql's message>" for anything
# else: not accepting connections, a timeout, and so on. Only "auth" justifies a new password.
pdb_password_check() {
    local out
    if out=$(printf '%s' "$1" | "$DOCKER" exec -i "$CONTAINER" sh -c \
            "PGPASSWORD=\"\$(cat)\" PGSSLMODE=require PGCONNECT_TIMEOUT=10 psql -X -h 127.0.0.1 -U '$DB_USERNAME' -d '$DB_NAME' -tAc 'SELECT 1'" 2>&1); then
        echo ok
    elif [[ "$out" == *"password authentication failed"* ]]; then
        echo auth
    else
        echo "error: ${out//$'\n'/ }"
    fi
}

# True if the published credentials log in as the app role.
pdb_credentials_work() {
    local json
    json=$(pdb_published_credentials) || return 1
    [[ "$(pdb_password_check "$(jq -r .password <<<"$json")")" == ok ]]
}

# Gives the app role a new password (sets DB_PASSWORD) and publishes it. If the publish fails, the
# start fails: runners cannot connect until a start succeeds.
pdb_rotate_password() {
    DB_PASSWORD=$(pdb_random_hex)
    pdb_psql <<SQL >/dev/null || pdb_die "could not set a new password for $DB_USERNAME"
ALTER ROLE "$DB_USERNAME" PASSWORD '$DB_PASSWORD';
SQL
    pdb_log "Set a new password for $DB_USERNAME"
    pdb_publish_credentials
}

# --------------------------------------------------------------------------- the start decision

# Decides what start.sh does. Pure: no side effects, so every combination is unit-tested.
#
#   $1 state        up | down | absent
#   $2 major        the running server's major version ("" unless up)
#   $3 overwrite    true | false   (OVERWRITE parameter)
#   $4 restore_from ""  | none | <file under backups/> | s3://...   (RESTORE_FROM parameter)
#   $5 live_state   clean | dirty | replacing | unknown   (what the live DB holds)
#   $6 if_failed    restore-clean | continue  (IF_LAST_RUN_FAILED parameter)
#   $7 last_source  for replacing: the source the unfinished replace was restoring
#   $8 last_next    for replacing: the state it would have recorded (clean|dirty)
#
# Sets PLAN_ACTION (keep|replace), PLAN_PRE_DUMP (yes|try|no), PLAN_SOURCE (restore source for
# pdb_source_uri), PLAN_NEXT_STATE (clean|dirty|same) and PLAN_REASON.
plan_start() {
    local state="$1" major="$2" overwrite="$3" restore_from="$4" live="$5" if_failed="$6"
    local last_source="${7:-}" last_next="${8:-}"
    PLAN_ACTION=replace PLAN_PRE_DUMP=no PLAN_SOURCE="" PLAN_NEXT_STATE=clean PLAN_REASON=""

    if [[ "$live" == replacing ]]; then
        # A replace removed the old database and did not finish: whatever is there now is a partial
        # restore. It is never kept, and never saved: saving it would overwrite
        # participant_db_pre_overwrite.tar, the old database's only copy.
        PLAN_REASON="the last start's replace did not finish; the live database is a partial restore"
        if [[ -n "$restore_from" ]]; then
            PLAN_SOURCE="$restore_from"
        else
            PLAN_SOURCE="${last_source:-clean}"
            PLAN_NEXT_STATE="${last_next:-clean}"
            PLAN_REASON+=", so its restore is retried"
        fi
    elif [[ "$state" == up ]]; then
        if [[ "$overwrite" == true ]]; then
            PLAN_PRE_DUMP=yes
            PLAN_SOURCE="${restore_from:-clean}"
            PLAN_REASON="OVERWRITE requested"
        elif [[ "$major" != "$PG_MAJOR" ]]; then
            PLAN_PRE_DUMP=yes
            PLAN_REASON="server is PostgreSQL ${major:-?}, not $PG_MAJOR"
            if [[ "$live" == dirty && "$if_failed" == restore-clean ]]; then
                PLAN_SOURCE=clean
                PLAN_REASON+="; it holds a failed run, so the clean backup is restored"
            else
                # Carry the data across the version change.
                PLAN_SOURCE=pre_overwrite
                PLAN_NEXT_STATE=same
            fi
        elif [[ "$live" == dirty && "$if_failed" == restore-clean ]]; then
            PLAN_PRE_DUMP=yes
            PLAN_SOURCE=clean
            PLAN_REASON="the last run failed and IF_LAST_RUN_FAILED=restore-clean"
        else
            PLAN_ACTION=keep
            PLAN_NEXT_STATE=same
            PLAN_REASON="up on PostgreSQL $major"
            if [[ "$live" == dirty ]]; then
                PLAN_REASON+=", continuing from the failed run's data (IF_LAST_RUN_FAILED=continue)"
            fi
            if [[ -n "$restore_from" ]]; then
                PLAN_REASON+="; RESTORE_FROM ignored without OVERWRITE"
            fi
        fi
    else
        if [[ "$state" == down ]]; then
            PLAN_PRE_DUMP=try
        fi
        PLAN_REASON="not up ($state)"
        if [[ -n "$restore_from" ]]; then
            PLAN_SOURCE="$restore_from"
        elif [[ "$live" == dirty && "$if_failed" == continue ]]; then
            PLAN_SOURCE=not_promoted
            PLAN_NEXT_STATE=dirty
        else
            PLAN_SOURCE=clean
        fi
    fi
}
