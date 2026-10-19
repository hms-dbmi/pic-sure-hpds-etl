#!/bin/bash
#
# Fails fast, on the Jenkins agent and before anything is provisioned, when the participant
# database is not ready. That means one of these: participant-db-start has not published the
# credentials runners connect with (SSM parameter ../common/db-credentials-path.sh <env>), the
# container on this Jenkins host is not accepting connections, or the published credentials do not
# log in (for example after a failed replace).
#
# Needs ssm:GetParameter on that parameter and kms:Decrypt on its key (see docs/PARTICIPANT_DB.md).
# The password itself is never printed.
#
# Usage: require-db.sh <env>
# Exit:  0 database up | 1 database down, or credentials missing or not working
set -uo pipefail

ENV_NAME="${1:?usage: require-db.sh <env>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../participant-db/lib.sh
source "$HERE/../participant-db/lib.sh"
pdb_init "$ENV_NAME"

down() {
    echo "ERROR: the participant database is not ready in '$ENV_NAME': $1"
    echo "       Run the participant-db-start job (orchestrators run it for you)."
    exit 1
}

RC=0
JSON=$(pdb_published_credentials) || RC=$?
case "$RC" in
    0) ;;
    2) down "no usable credentials published at SSM parameter $CREDS_PARAM" ;;
    *) down "cannot read SSM parameter $CREDS_PARAM (error above)" ;;
esac
STATE=$(pdb_state)
[[ "$STATE" == up ]] || down "container $CONTAINER is $STATE"
CHECK=$(pdb_password_check "$(jq -r .password <<<"$JSON")")
[[ "$CHECK" == ok ]] || down "the published credentials do not log in to $CONTAINER ($CHECK)"

echo "Participant database is up ($CONTAINER, $(jq -r '.host' <<<"$JSON"):$(jq -r '.port' <<<"$JSON"))."
