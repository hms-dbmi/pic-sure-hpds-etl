#!/bin/bash
#
# Fails fast, on the Jenkins agent and before anything is provisioned, when the participant
# database is not up. The DB secret exists only between participant-db-start and
# participant-db-stop, and the start job writes its value only once Postgres has restored and
# is accepting connections -- so "secret has a current value" means "database is ready".
#
# Only describe-secret is called: the value itself is never read here.
#
# Usage: require-db.sh <env>          (reads db_secret_id from environments/<env>.tfvars)
# Exit:  0 database up | 1 database down or unreachable secret
set -uo pipefail

ENV_NAME="${1:?usage: require-db.sh <env>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_TFVARS="$HERE/../environments/$ENV_NAME.tfvars"

tfvar() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\(.*\)\"/\1/p" "$ENV_TFVARS"; }

SECRET_ID="$(tfvar db_secret_id)"
REGION="${AWS_REGION:-$(tfvar aws_region)}"

if [[ -z "$SECRET_ID" ]]; then
    echo "ERROR: db_secret_id is not set in $ENV_TFVARS"
    exit 1
fi

STAGES=$(aws secretsmanager describe-secret --secret-id "$SECRET_ID" --region "$REGION" \
           --query 'VersionIdsToStages' --output json 2>/dev/null) || STAGES=''

if [[ -z "$STAGES" ]] || ! jq -e '[.[][]] | index("AWSCURRENT")' <<<"$STAGES" >/dev/null 2>&1; then
    echo "ERROR: the participant database is not running in '$ENV_NAME' ($SECRET_ID has no current value)."
    echo "       Run the participant-db-start job first, and participant-db-stop when finished --"
    echo "       or run this job through an orchestrator, which starts and stops it for you."
    exit 1
fi

echo "Participant database is up ($SECRET_ID)."
