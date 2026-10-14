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
#                live_state.json                   whether the live DB is clean or holds a failed run
#   creds      ../common/db-credentials-path.sh <ENV>, mode 600: what DB-backed runners connect with
#
# Admin work runs as the postgres superuser over the container's local socket, so no superuser
# password is ever needed or stored. The app role's password is generated here and passed to
# psql on stdin, never on a command line.
#
# xtrace must stay off in anything sourcing this: it handles the app password.

PDB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=participant-db.conf
source "$PDB_DIR/participant-db.conf"
DOCKER="${DOCKER:-docker}"

pdb_log() { echo "[$(date -u +%H:%M:%S)] [participant-db] $*"; }
pdb_die() { pdb_log "ERROR: $*" >&2; exit 1; }

# Sets every per-environment name. Call first.
pdb_init() {
    ENV_NAME="${1:?pdb_init <env>}"
    CONTAINER="hpds-etl-participant-db-$ENV_NAME"
    VOLUME="$CONTAINER-data"
    BACKUP_KEY_PREFIX="$BACKUP_PREFIX/$ENV_NAME/backups"
    BACKUP_URI="s3://$BACKUP_BUCKET/$BACKUP_KEY_PREFIX"
    CREDS="$("$PDB_DIR/../common/db-credentials-path.sh" "$ENV_NAME")"
    # PDB_ENV_DIR: tests point this at their own environment files.
    local tfvars="${PDB_ENV_DIR:-$PDB_DIR/../environments}/$ENV_NAME.tfvars"
    [[ -f "$tfvars" ]] || pdb_die "no environment file $tfvars"
    REGION="${AWS_REGION:-$(pdb_tfvar aws_region "$tfvars")}"
    DB_HOST_CONFIGURED="$(pdb_tfvar participant_db_host "$tfvars")"
}

pdb_tfvar() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\(.*\)\"/\1/p" "$2"; }

# --------------------------------------------------------------------------- container

pdb_container_exists() { "$DOCKER" container inspect "$CONTAINER" >/dev/null 2>&1; }

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
    "$DOCKER" pull -q "$PG_IMAGE" >/dev/null || pdb_die "could not pull $PG_IMAGE"
    "$DOCKER" volume create "$VOLUME" >/dev/null || pdb_die "could not create volume $VOLUME"
    # The image refuses to initialise without a superuser password. It is never used (admin
    # work goes over the local socket), so it is random and not kept.
    "$DOCKER" run -d --name "$CONTAINER" --restart=unless-stopped \
        -p "$DB_PORT:5432" \
        -v "$VOLUME:/var/lib/postgresql/data" \
        -e "POSTGRES_PASSWORD=$(pdb_random_hex)" \
        "$PG_IMAGE" \
        -c "max_connections=$MAX_CONNECTIONS" -c "shared_buffers=$SHARED_BUFFERS" \
        -c password_encryption=scram-sha-256 >/dev/null \
        || pdb_die "could not start $CONTAINER"
    pdb_wait_ready
}

# Removes the container and its data volume. Callers have already saved anything worth keeping.
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

# clean | dirty | unknown: what the live database holds, as recorded by the last start or backup.
pdb_read_live_state() {
    local json
    json=$(aws s3 cp "$BACKUP_URI/live_state.json" - --region "$REGION" 2>/dev/null) || { echo unknown; return; }
    local state
    state=$(jq -r '.state // "unknown"' <<<"$json" 2>/dev/null)
    case "$state" in clean|dirty) echo "$state" ;; *) echo unknown ;; esac
}

pdb_write_live_state() {
    local state="$1" reason="$2"
    jq -n --arg state "$state" --arg reason "$reason" --arg env "$ENV_NAME" \
          --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg build "${BUILD_TAG:-manual}" \
          '{state: $state, reason: $reason, env: $env, at: $at, build: $build}' \
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
    set -o pipefail
    "$DOCKER" exec "$CONTAINER" cat "/tmp/pdb-dump/$name.tar" \
        | aws s3 cp - "$BACKUP_URI/$name.tar" --region "$REGION" --no-progress \
            --expected-size "$bytes" --metadata "dumped-at=$ts" \
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

# Creates the app role (with a new password), its database, and then either restores the dump at
# <uri> or creates the schema from schema.sql. Prints nothing; sets DB_PASSWORD.
pdb_load() {
    local uri="$1" toc
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

# Writes the 600-mode credentials file DB-backed runners read (same fields as before).
pdb_write_credentials() {
    local host
    host=$(pdb_host)
    mkdir -p "$(dirname "$CREDS")"
    chmod 700 "$(dirname "$CREDS")"
    local tmp
    tmp=$(umask 077; mktemp "$CREDS.XXXXXX")
    jq -n --arg host "$host" --argjson port "$DB_PORT" --arg db "$DB_NAME" --arg schema "$DB_SCHEMA" \
          --arg user "$DB_USERNAME" --arg pw "$DB_PASSWORD" --arg container "$CONTAINER" \
          '{engine: "postgres", host: $host, port: $port, dbname: $db, schema: $schema,
            username: $user, password: $pw, container: $container}' > "$tmp" \
        && mv "$tmp" "$CREDS" || { rm -f "$tmp"; pdb_die "could not write $CREDS"; }
    pdb_log "Saved connection details to $CREDS (host $host:$DB_PORT)"
}

# True if the saved credentials log in as the app role (TCP, password on stdin).
pdb_credentials_work() {
    [[ -f "$CREDS" ]] || return 1
    local pw
    pw=$(jq -er '.password' "$CREDS" 2>/dev/null) || return 1
    printf '%s' "$pw" | "$DOCKER" exec -i "$CONTAINER" sh -c \
        "PGPASSWORD=\"\$(cat)\" psql -X -h 127.0.0.1 -U '$DB_USERNAME' -d '$DB_NAME' -tAc 'SELECT 1'" >/dev/null 2>&1
}

# Gives the app role a new password and saves it. For a kept database whose credentials file is
# gone or stale (e.g. the Jenkins home was rebuilt).
pdb_reset_credentials() {
    DB_PASSWORD=$(pdb_random_hex)
    pdb_psql <<SQL >/dev/null || pdb_die "could not reset the password of $DB_USERNAME"
ALTER ROLE "$DB_USERNAME" PASSWORD '$DB_PASSWORD';
SQL
    pdb_write_credentials
}

# --------------------------------------------------------------------------- the start decision

# Decides what start.sh does. Pure: no side effects, so every combination is unit-tested.
#
#   $1 state        up | down | absent
#   $2 major        the running server's major version ("" unless up)
#   $3 overwrite    true | false   (OVERWRITE parameter)
#   $4 restore_from ""  | none | <file under backups/> | s3://...   (RESTORE_FROM parameter)
#   $5 live_state   clean | dirty | unknown   (what the live DB holds)
#   $6 if_failed    restore-clean | continue  (IF_LAST_RUN_FAILED parameter)
#
# Sets PLAN_ACTION (keep|replace), PLAN_PRE_DUMP (yes|try|no), PLAN_SOURCE (restore source for
# pdb_source_uri), PLAN_NEXT_STATE (clean|dirty|same) and PLAN_REASON.
plan_start() {
    local state="$1" major="$2" overwrite="$3" restore_from="$4" live="$5" if_failed="$6"
    PLAN_ACTION=replace PLAN_PRE_DUMP=no PLAN_SOURCE="" PLAN_NEXT_STATE=clean PLAN_REASON=""

    if [[ "$state" == up ]]; then
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
