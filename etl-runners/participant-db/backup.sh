#!/bin/bash
#
# participant-db-backup: dump the participant database to S3 at the end of a pipeline run. The
# database stays up.
#
#   promote=true   overwrites participant_db.tar, the clean restore point; live data recorded clean
#   promote=false  overwrites participant_db_not_promoted.tar (a failed or aborted run); the clean
#                  restore point is untouched, and the live data is recorded dirty, so by default
#                  the next start restores the clean backup (IF_LAST_RUN_FAILED)
#
# A pipeline run's start (start.sh with RUN_ID) already recorded the live data dirty, so a run that
# never gets here (killed, agent lost, this backup failing) still counts as failed: only
# promote=true records it clean.
#
# The bucket must be versioned: each overwrite keeps the previous dump as an older version.
#
# Usage:  backup.sh <env> <promote: true|false>
# Last line on success:  BACKUP_RESULT file=<name>.tar promoted=<bool>
# Exit:   0 dump uploaded and verified | 1 anything else
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

pdb_init "${1:?usage: backup.sh <env> <promote>}"
PROMOTE="${2:?usage: backup.sh <env> <promote: true|false>}"
case "$PROMOTE" in true|false) ;; *) pdb_die "promote must be true or false, got '$PROMOTE'" ;; esac

pdb_require_versioning
# A replace that did not finish leaves a partial restore up. Dumping it would put it over the
# clean restore point (promote=true), or mark it dirty so the next start saves it over
# participant_db_pre_overwrite.tar, the replaced database's copy (promote=false).
LIVE_RECORD=$(pdb_read_live_record) || exit 1
[[ "$(jq -r .state <<<"$LIVE_RECORD")" != replacing ]] \
    || pdb_die "the live database is a partial restore (a participant-db-start replace did not finish), so nothing was dumped. Re-run participant-db-start first."
STATE=$(pdb_state)
[[ "$STATE" == up ]] || pdb_die "$CONTAINER is $STATE, so nothing was dumped. Its data is still in volume $VOLUME; start the container (or run participant-db-start) and re-run this backup."

if [[ "$PROMOTE" == true ]]; then
    NAME=participant_db
else
    NAME=participant_db_not_promoted
fi
pdb_dump_to_s3 "$NAME"

if [[ "$PROMOTE" == true ]]; then
    pdb_write_live_state clean "backed up as the clean restore point"
    pdb_log "participant_db.tar replaced: it is the new clean restore point"
else
    pdb_write_live_state dirty "the run failed; backed up to participant_db_not_promoted.tar"
    pdb_log "participant_db.tar unchanged: the run did not succeed"
fi
echo "BACKUP_RESULT file=$NAME.tar promoted=$PROMOTE"
