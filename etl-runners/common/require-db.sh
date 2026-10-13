#!/bin/bash
#
# Fails fast, on the Jenkins agent and before anything is provisioned, when the participant
# database is not up: its container on this Jenkins host is not accepting connections, or
# participant-db-start has not saved the credentials runners connect with.
#
# The password itself is never printed.
#
# Usage: require-db.sh <env>
# Exit:  0 database up | 1 database down, or credentials missing
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

[[ -f "$CREDS" ]] || down "no credentials at $CREDS"
jq -e '.host and .password' "$CREDS" >/dev/null 2>&1 || down "$CREDS is missing host or password"
STATE=$(pdb_state)
[[ "$STATE" == up ]] || down "container $CONTAINER is $STATE"

echo "Participant database is up ($CONTAINER, $(jq -r '.host' "$CREDS"):$(jq -r '.port' "$CREDS"))."
