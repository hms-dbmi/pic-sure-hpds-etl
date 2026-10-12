#!/bin/bash
#
# Fails fast, on the Jenkins agent and before anything is provisioned, when the participant
# database is not up. participant-db-start saves the database's credentials on the Jenkins
# server only once Postgres has restored and is accepting connections, and participant-db-stop
# deletes them -- so "credentials file present, and its instance running" means "database
# ready".
#
# The password itself is never printed.
#
# Usage: require-db.sh <env>
# Exit:  0 database up | 1 database down, or credentials missing
set -uo pipefail

ENV_NAME="${1:?usage: require-db.sh <env>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_TFVARS="$HERE/../environments/$ENV_NAME.tfvars"
CREDS="$("$HERE/db-credentials-path.sh" "$ENV_NAME")"

tfvar() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\(.*\)\"/\1/p" "$ENV_TFVARS"; }
REGION="${AWS_REGION:-$(tfvar aws_region)}"

down() {
    echo "ERROR: the participant database is not running in '$ENV_NAME': $1"
    echo "       Run the participant-db-start job first, and participant-db-stop when finished --"
    echo "       or run this job through an orchestrator, which starts and stops it for you."
    exit 1
}

[[ -f "$CREDS" ]] || down "no credentials at $CREDS"
INSTANCE_ID=$(jq -er '.instanceId' "$CREDS" 2>/dev/null) || down "$CREDS has no instanceId"
jq -e '.host and .password' "$CREDS" >/dev/null 2>&1 || down "$CREDS is missing host or password"

STATE=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --region "$REGION" \
          --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo unknown)
[[ "$STATE" == "running" ]] || down "its instance $INSTANCE_ID is '$STATE' (stale $CREDS?)"

echo "Participant database is up ($INSTANCE_ID, $(jq -r '.host' "$CREDS"))."
