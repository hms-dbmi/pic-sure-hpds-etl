#!/bin/bash
#
# participant-db-start: make sure the participant database is up on the Jenkins host, then publish the
# connection details DB-backed runners read. See lib.sh for names and locations.
#
#   up, PostgreSQL 16, live DB clean         keep it (refresh the saved credentials)
#   up, but OVERWRITE=true                   save it to participant_db_pre_overwrite.tar, then
#                                            replace it with RESTORE_FROM (default: the clean backup)
#   up, but the last run failed              IF_LAST_RUN_FAILED=restore-clean (default): as OVERWRITE,
#                                            from the clean backup. =continue: keep it.
#   up, but not PostgreSQL 16                save it, recreate on 16, restore what was saved (or the
#                                            clean backup, if it holds a failed run and restore-clean)
#   not up (stopped, or never created)       create it from RESTORE_FROM, else the clean backup
#                                            (or, after a failed run with continue, that run's dump).
#                                            A stopped container, or a data volume left without its
#                                            container, is started and saved first. If it does not
#                                            come up, nothing is removed and the start fails, unless
#                                            OVERWRITE=true discards it.
#   the last replace did not finish          (live state "replacing") replace the partial restore,
#                                            unsaved, from RESTORE_FROM, else retry that replace's source
#
# The restore source is resolved before anything is removed, and a data volume is only removed
# once its database is saved (and the save verified), or it is a partial restore, or OVERWRITE=true
# discarded it. A replace records the live state "replacing" before it removes anything; if it
# then fails, re-running start retries it without saving the partial restore, so
# participant_db_pre_overwrite.tar keeps the replaced database.
#
# With RUN_ID set (orchestrators pass their BUILD_TAG), the live database is recorded dirty ("run in
# progress") once it is up, so that only that run's promoted backup marks it clean again: a run
# killed before its backup is still treated as failed.
#
# Credentials: the app role's connection details go to the SSM SecureString
# ../common/db-credentials-path.sh <env>, overwritten on every successful start. The password is:
#   replace                      new (the role is created again)
#   keep, with RUN_ID            new: rotated on every pipeline run's start (ALTER ROLE)
#   keep, without RUN_ID         unchanged if the published one logs in. A new one only when the
#                                server rejects it, or none is published. Any other failure (not
#                                accepting connections, SSM errors) fails the start and changes
#                                nothing, so a standalone start never rotates under running jobs.
# If publishing fails, the start fails.
#
# The server runs with TLS (ssl=on, a self-signed certificate in its config volume), and pg_hba
# allows the app role only over TLS and the superuser only on the local socket. A kept container
# that predates this is recreated on the same data volume. That happens once, standalone or not,
# and it restarts Postgres: open connections are dropped. So run that first start when no
# DB-backed job is running.
#
# Usage:  start.sh <env>
# Env:    OVERWRITE=true|false  RESTORE_FROM=<''|none|file under backups/|s3://...>
#         IF_LAST_RUN_FAILED=restore-clean|continue  RUN_ID=<pipeline run id, or blank>
# Exit:   0 database up and credentials published | 1 anything else
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

# Leading and trailing whitespace removed. (xargs would also strip quotes, and abort on an
# unmatched one.)
trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

pdb_init "${1:?usage: start.sh <env>}"
OVERWRITE="${OVERWRITE:-false}"
RESTORE_FROM="$(trim "${RESTORE_FROM:-}")"
IF_LAST_RUN_FAILED="${IF_LAST_RUN_FAILED:-restore-clean}"
RUN_ID="$(trim "${RUN_ID:-}")"
case "$OVERWRITE" in true|false) ;; *) pdb_die "OVERWRITE must be true or false, got '$OVERWRITE'" ;; esac
case "$IF_LAST_RUN_FAILED" in restore-clean|continue) ;; *) pdb_die "IF_LAST_RUN_FAILED must be restore-clean or continue, got '$IF_LAST_RUN_FAILED'" ;; esac
# An S3 key or URI: no whitespace, quotes or shell metacharacters.
[[ "$RESTORE_FROM" =~ ^[A-Za-z0-9._/:@+=,-]*$ ]] \
    || pdb_die "RESTORE_FROM must be blank, none, a file under backups/ or an s3:// URI (letters, digits and . _ / : @ + = , - only), got '$RESTORE_FROM'"
if [[ "$RESTORE_FROM" == s3://* ]]; then
    REST="${RESTORE_FROM#s3://}"
    [[ "$REST" == */?* && "${REST%%/*}" != "" ]] || pdb_die "RESTORE_FROM '$RESTORE_FROM' is not an s3://<bucket>/<key> URI"
elif [[ "$RESTORE_FROM" == *://* || "$RESTORE_FROM" == /* ]]; then
    pdb_die "RESTORE_FROM '$RESTORE_FROM' is neither a file under backups/ nor an s3:// URI"
fi
[[ "$RUN_ID" != *[[:cntrl:]]* ]] || pdb_die "RUN_ID must be a single line without control characters"

# The Jenkins-side credentials file of earlier versions: SSM now holds the only copy.
rm -f "${HPDS_ETL_CREDENTIALS_DIR:-$HOME/.hpds-etl}/participant-db-$ENV_NAME.json"

"$DOCKER" info >/dev/null 2>&1 || pdb_die "cannot reach the container runtime through '$DOCKER' (is the podman socket mounted?)"
pdb_require_versioning

STATE=$(pdb_state)
MAJOR=""
if [[ "$STATE" == up ]]; then
    MAJOR=$(pdb_server_major) || pdb_die "$CONTAINER is up but its version could not be read"
fi
LIVE_RECORD=$(pdb_read_live_record) || exit 1
LIVE=$(jq -r .state <<<"$LIVE_RECORD")
pdb_log "Environment $ENV_NAME: $CONTAINER is $STATE${MAJOR:+ (PostgreSQL $MAJOR)}; live data is $LIVE"

# A data volume without its container (removed by hand, say) still holds the live database: run a
# container on it, so it is treated like a stopped one and saved before anything replaces it.
if [[ "$STATE" == absent && "$LIVE" != replacing ]] && pdb_volume_exists; then
    pdb_log "$CONTAINER is absent but its volume $VOLUME exists: starting a container on it to save its data first"
    pdb_run_container
    STATE=down
fi

plan_start "$STATE" "$MAJOR" "$OVERWRITE" "$RESTORE_FROM" "$LIVE" "$IF_LAST_RUN_FAILED" \
    "$(jq -r '.source // ""' <<<"$LIVE_RECORD")" "$(jq -r '.next // ""' <<<"$LIVE_RECORD")"
pdb_log "Plan: $PLAN_ACTION ($PLAN_REASON)${PLAN_SOURCE:+, restore from $PLAN_SOURCE}"

# Recorded at the end of a start for a pipeline run: only its promoted backup marks it clean.
RUN_REASON="run $RUN_ID in progress (only a promoted backup records it clean)"

if [[ "$PLAN_ACTION" == keep ]]; then
    pdb_require_current_config
    if [[ -n "$RUN_ID" ]]; then
        pdb_log "Rotating the password of $DB_USERNAME for run $RUN_ID"
        pdb_rotate_password
    else
        PUB_RC=0
        PUBLISHED=$(pdb_published_credentials) || PUB_RC=$?
        case "$PUB_RC" in
            0)
                CHECK=$(pdb_password_check "$(jq -r .password <<<"$PUBLISHED")")
                case "$CHECK" in
                    ok)
                        DB_PASSWORD=$(jq -r .password <<<"$PUBLISHED")
                        pdb_publish_credentials   # refreshes the host, in case it changed
                        ;;
                    auth)
                        pdb_log "The published password for $DB_USERNAME is rejected; issuing a new one"
                        pdb_rotate_password
                        ;;
                    *)
                        pdb_die "could not check the published credentials against $CONTAINER (${CHECK#error: }). The password was not changed; re-run once the database accepts connections."
                        ;;
                esac
                ;;
            2)
                pdb_log "No usable credentials published at $CREDS_PARAM; issuing a new password for $DB_USERNAME"
                pdb_rotate_password
                ;;
            *) exit 1 ;;
        esac
        unset PUBLISHED
    fi
    unset DB_PASSWORD
    pdb_credentials_work || pdb_die "the published credentials do not log in to $CONTAINER"
    if [[ -n "$RUN_ID" ]]; then
        pdb_write_live_state dirty "$RUN_REASON; kept a running database that was $LIVE"
    elif [[ "$LIVE" == unknown ]]; then
        pdb_write_live_state clean "kept a running database with no recorded state"
    fi
    pdb_log "Participant database is up: $CONTAINER (kept)"
    exit 0
fi

# ---- replace -----------------------------------------------------------------------------------

# Saving the live database writes participant_db_pre_overwrite.tar, so that file can't also be
# what replaces it: the restore would load the save just made. (The recovery after a failed
# replace is unaffected: a partial restore is never saved.)
if [[ "$PLAN_PRE_DUMP" != no && -n "$RESTORE_FROM" && "$PLAN_SOURCE" == "$RESTORE_FROM" ]]; then
    case "$RESTORE_FROM" in
        pre_overwrite|participant_db_pre_overwrite.tar|"$BACKUP_URI/participant_db_pre_overwrite.tar")
            pdb_die "RESTORE_FROM=$RESTORE_FROM: this start must first save the live database to that same file, which would replace what you asked to restore. Nothing was changed. Copy it under another name (aws s3 cp $BACKUP_URI/participant_db_pre_overwrite.tar $BACKUP_URI/<name>.tar) and set RESTORE_FROM=<name>.tar." ;;
    esac
fi

PRE_DUMPED=false
case "$PLAN_PRE_DUMP" in
    yes)
        pdb_log "Saving the live database before replacing it"
        pdb_dump_to_s3 participant_db_pre_overwrite
        PRE_DUMPED=true
        ;;
    try)
        # A stopped (or not yet accepting) container may still hold writes no dump captured: start
        # it and save them. If it doesn't come up, its volume is left alone unless OVERWRITE=true.
        WAIT_FOR="${RESTART_STOPPED_TIMEOUT:-$RESTART_STOPPED_TIMEOUT_SECONDS}"
        pdb_log "Starting $CONTAINER to save its data first (waiting up to ${WAIT_FOR}s)"
        "$DOCKER" start "$CONTAINER" >/dev/null 2>&1 || true
        if pdb_wait_accepting "$WAIT_FOR"; then
            pdb_dump_to_s3 participant_db_pre_overwrite
            PRE_DUMPED=true
        elif [[ "$OVERWRITE" == true ]]; then
            pdb_log "WARNING: $CONTAINER did not come up within ${WAIT_FOR}s; OVERWRITE=true, so its unsaved data in volume $VOLUME is discarded"
        else
            "$DOCKER" logs --tail 40 "$CONTAINER" 2>&1 | sed 's/^/    /' >&2 || true
            pdb_die "$CONTAINER did not accept connections within ${WAIT_FOR}s, so its data could not be saved. Nothing was removed: the data is still in volume $VOLUME (container log above). If Postgres is still recovering (for example after a host reboot), re-run once it accepts connections. If the volume holds another PostgreSQL major version, it cannot start on $PG_IMAGE: dump it with a container on that version. To discard that data and restore from a backup instead, re-run with OVERWRITE=true (and RESTORE_FROM)."
        fi
        ;;
esac

# Resolved before removing anything: a missing backup must fail with the database untouched.
URI=$(pdb_source_uri "$PLAN_SOURCE") || pdb_die "restore source '$PLAN_SOURCE' is not usable; nothing was changed"
NEED_BYTES=$(pdb_restore_bytes_needed "$URI") || pdb_die "restore source $URI is not usable; nothing was changed"
# A first space check while nothing is removed yet, counting what removing the live database
# frees. (pdb_load checks again in the new container, before downloading.)
if [[ "$URI" != none ]] && pdb_accepting; then
    LIVE_BYTES=$(pdb_psql -tAc "SELECT coalesce(sum(pg_database_size(datname)), 0) FROM pg_database" 2>/dev/null | tr -d '[:space:]') || LIVE_BYTES=0
    [[ "$LIVE_BYTES" =~ ^[0-9]+$ ]] || LIVE_BYTES=0
    pdb_require_restore_space "$URI" "$NEED_BYTES" "$LIVE_BYTES"
fi
NEXT_STATE="$PLAN_NEXT_STATE"
if [[ "$NEXT_STATE" == same ]]; then
    NEXT_STATE=$([[ "$LIVE" == dirty ]] && echo dirty || echo clean)
fi

on_exit() {
    local rc=$?
    if (( rc != 0 )) && [[ "${REMOVED:-false}" == true ]]; then
        pdb_log "ERROR: the database was replaced but not brought back up. It is recorded as 'replacing':" >&2
        pdb_log "       re-run participant-db-start to retry the restore from ${URI/#none/a fresh schema} (the partial restore is not saved)." >&2
        if [[ "$PRE_DUMPED" == true ]]; then
            pdb_log "       Its previous contents are in $BACKUP_URI/participant_db_pre_overwrite.tar:" >&2
            pdb_log "       to restore those instead, re-run with OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar." >&2
        fi
        # Nothing to withdraw: the published credentials are the replaced database's (or this
        # restore's, if it got that far), and require-db.sh checks that they log in.
    fi
}
trap on_exit EXIT

# Before anything is removed: from here until the replace finishes, the live database is not
# trustworthy, and the next start must neither keep it nor save it.
pdb_write_live_state replacing "replacing $CONTAINER from ${URI/#none/a fresh schema} ($PLAN_REASON)" "$URI" "$NEXT_STATE"
REMOVED=true
pdb_remove_container
pdb_create_container
pdb_load "$URI" "$NEED_BYTES"
pdb_publish_credentials
unset DB_PASSWORD
pdb_credentials_work || pdb_die "the published credentials do not log in to $CONTAINER"
# Restored. If recording that fails, the start still fails, and the state left at "replacing"
# makes the next start redo this restore: wasted time, but nothing lost.
REMOVED=false
if [[ -n "$RUN_ID" ]]; then
    pdb_write_live_state dirty "$RUN_REASON; restored from ${URI/#none/a fresh schema} ($PLAN_REASON)"
else
    pdb_write_live_state "$NEXT_STATE" "restored from ${URI/#none/a fresh schema} ($PLAN_REASON)"
fi
pdb_log "Participant database is up: $CONTAINER (PostgreSQL $(pdb_server_major)), restored from ${URI/#none/a fresh schema}"
