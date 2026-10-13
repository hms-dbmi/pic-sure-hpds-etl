#!/bin/bash
#
# participant-db-start: make sure the participant database is up on the Jenkins host, then save the
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
#                                            A stopped container is started and saved first if it can be.
#
# The restore source is resolved before anything is removed, and a live database is always saved
# (and the save verified) before it is replaced. If a replace fails after removal, re-run with
# OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar.
#
# Usage:  start.sh <env>
# Env:    OVERWRITE=true|false  RESTORE_FROM=<''|none|file under backups/|s3://...>
#         IF_LAST_RUN_FAILED=restore-clean|continue
# Exit:   0 database up and credentials saved | 1 anything else
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

pdb_init "${1:?usage: start.sh <env>}"
OVERWRITE="${OVERWRITE:-false}"
RESTORE_FROM="$(echo "${RESTORE_FROM:-}" | xargs)"
IF_LAST_RUN_FAILED="${IF_LAST_RUN_FAILED:-restore-clean}"
case "$OVERWRITE" in true|false) ;; *) pdb_die "OVERWRITE must be true or false, got '$OVERWRITE'" ;; esac
case "$IF_LAST_RUN_FAILED" in restore-clean|continue) ;; *) pdb_die "IF_LAST_RUN_FAILED must be restore-clean or continue, got '$IF_LAST_RUN_FAILED'" ;; esac

"$DOCKER" info >/dev/null 2>&1 || pdb_die "cannot reach the container runtime through '$DOCKER' (is the podman socket mounted?)"
pdb_require_versioning

STATE=$(pdb_state)
MAJOR=""
if [[ "$STATE" == up ]]; then
    MAJOR=$(pdb_server_major) || pdb_die "$CONTAINER is up but its version could not be read"
fi
LIVE=$(pdb_read_live_state)
pdb_log "Environment $ENV_NAME: $CONTAINER is $STATE${MAJOR:+ (PostgreSQL $MAJOR)}; live data is $LIVE"

plan_start "$STATE" "$MAJOR" "$OVERWRITE" "$RESTORE_FROM" "$LIVE" "$IF_LAST_RUN_FAILED"
pdb_log "Plan: $PLAN_ACTION ($PLAN_REASON)${PLAN_SOURCE:+, restore from $PLAN_SOURCE}"

if [[ "$PLAN_ACTION" == keep ]]; then
    if pdb_credentials_work; then
        DB_PASSWORD=$(jq -r '.password' "$CREDS")
        pdb_write_credentials   # refreshes the host, in case it changed
    else
        pdb_log "Saved credentials missing or stale; issuing a new password for $DB_USERNAME"
        pdb_reset_credentials
    fi
    unset DB_PASSWORD
    if [[ "$LIVE" == unknown ]]; then
        pdb_write_live_state clean "kept a running database with no recorded state"
    fi
    pdb_log "Participant database is up: $CONTAINER (kept)"
    exit 0
fi

# ---- replace -----------------------------------------------------------------------------------

PRE_DUMPED=false
case "$PLAN_PRE_DUMP" in
    yes)
        pdb_log "Saving the live database before replacing it"
        pdb_dump_to_s3 participant_db_pre_overwrite
        PRE_DUMPED=true
        ;;
    try)
        # A stopped container may still hold writes no dump captured: start it and save them if it
        # comes up. If it can't, there is nothing readable to save.
        pdb_log "Starting the stopped $CONTAINER to save its data first"
        "$DOCKER" start "$CONTAINER" >/dev/null 2>&1 || true
        WAITED=0
        until pdb_accepting || (( WAITED >= ${RESTART_STOPPED_TIMEOUT:-$RESTART_STOPPED_TIMEOUT_SECONDS} )); do
            sleep 2; WAITED=$((WAITED + 2))
        done
        if pdb_accepting; then
            pdb_dump_to_s3 participant_db_pre_overwrite
            PRE_DUMPED=true
        else
            pdb_log "WARNING: $CONTAINER did not come up, so its data could not be saved before replacing it"
        fi
        ;;
esac

# Resolved before removing anything: a missing backup must fail with the database untouched.
URI=$(pdb_source_uri "$PLAN_SOURCE") || pdb_die "restore source '$PLAN_SOURCE' is not usable; nothing was changed"
NEXT_STATE="$PLAN_NEXT_STATE"
if [[ "$NEXT_STATE" == same ]]; then
    NEXT_STATE=$([[ "$LIVE" == dirty ]] && echo dirty || echo clean)
fi

on_exit() {
    local rc=$?
    if (( rc != 0 )) && [[ "${REMOVED:-false}" == true ]]; then
        pdb_log "ERROR: the database was replaced but not brought back up." >&2
        if [[ "$PRE_DUMPED" == true ]]; then
            pdb_log "       Its previous contents are in $BACKUP_URI/participant_db_pre_overwrite.tar:" >&2
            pdb_log "       re-run with OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar to restore them." >&2
        fi
        rm -f "$CREDS"
    fi
}
trap on_exit EXIT

REMOVED=true
pdb_remove_container
pdb_create_container
pdb_load "$URI"
pdb_write_credentials
unset DB_PASSWORD
pdb_credentials_work || pdb_die "the new credentials do not log in"
pdb_write_live_state "$NEXT_STATE" "restored from ${URI/#none/a fresh schema} ($PLAN_REASON)"
REMOVED=false
pdb_log "Participant database is up: $CONTAINER (PostgreSQL $(pdb_server_major)), restored from ${URI/#none/a fresh schema}"
