#!/bin/bash
#
# Copies the participant database's credentials from the DB instance to the Jenkins server.
# The instance writes them to a root-only file at the end of a successful boot; this reads
# that file over SSM Run Command and saves it, mode 600, at db-credentials-path.sh's path,
# where DB-backed runner jobs pick it up.
#
# The password passes through SSM command output, which SSM keeps in its command history. It
# is never printed to the Jenkins console.
#
# Usage: fetch-credentials.sh <instance-id> <env>
# Exit:  0 saved | 1 anything else
set -uo pipefail

INSTANCE_ID="${1:?instance id}"
ENV_NAME="${2:?env}"
REGION="${AWS_REGION:-us-east-1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CREDS="$("$HERE/../common/db-credentials-path.sh" "$ENV_NAME")"
REMOTE_FILE=/etc/participant-db/credentials.json
POLL=5
TIMEOUT=300

CMD_ID=$(aws ssm send-command --instance-ids "$INSTANCE_ID" --region "$REGION" \
           --document-name AWS-RunShellScript --comment "participant-db credentials" \
           --parameters "commands=[\"cat $REMOTE_FILE\"]" \
           --query Command.CommandId --output text) || { echo "ERROR: ssm send-command failed"; exit 1; }

START=$(date +%s)
while :; do
    # InvocationDoesNotExist is normal for a few seconds after send-command; any other error
    # (e.g. AccessDenied) is fatal and shown, not mistaken for Pending.
    if ! STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
                    --region "$REGION" --query Status --output text 2>&1); then
        if [[ "$STATUS" != *InvocationDoesNotExist* ]]; then
            echo "ERROR: ssm get-command-invocation failed: $STATUS"
            exit 1
        fi
        STATUS=Pending
    fi
    case "$STATUS" in Pending|InProgress|Delayed) ;; *) break ;; esac
    if (( $(date +%s) - START >= TIMEOUT )); then
        echo "ERROR: gave up waiting for the credentials command (last status $STATUS)."
        echo "       Pending means the instance's SSM agent never picked it up: check that the instance"
        echo "       is Online in Systems Manager > Fleet Manager (AmazonSSMManagedInstanceCore on its"
        echo "       role, and a network path to the ssm/ssmmessages/ec2messages endpoints)."
        exit 1
    fi
    sleep "$POLL"
done
if [[ "$STATUS" != "Success" ]]; then
    echo "ERROR: reading $REMOTE_FILE on $INSTANCE_ID ended $STATUS"
    exit 1
fi

JSON=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
         --region "$REGION" --query StandardOutputContent --output text 2>/dev/null)
if ! jq -e '.host and .password and .username and .dbname' >/dev/null 2>&1 <<<"$JSON"; then
    echo "ERROR: $REMOTE_FILE on $INSTANCE_ID is not the expected credentials JSON"
    exit 1
fi

# Written whole, then moved into place, so a reader never sees a partial file.
mkdir -p "$(dirname "$CREDS")"
chmod 700 "$(dirname "$CREDS")"
TMP=$(umask 077; mktemp "$CREDS.XXXXXX")
jq --arg id "$INSTANCE_ID" '. + {instanceId: $id}' <<<"$JSON" > "$TMP" && mv "$TMP" "$CREDS" \
    || { rm -f "$TMP"; echo "ERROR: could not write $CREDS"; exit 1; }
unset JSON
echo "Saved participant DB credentials for '$ENV_NAME' to $CREDS"
