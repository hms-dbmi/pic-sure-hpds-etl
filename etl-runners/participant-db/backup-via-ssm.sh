#!/bin/bash
#
# Runs participant-db-backup on the DB instance through SSM Run Command, waits for it, and
# confirms from the agent side that the dump it reports is really in S3. Only after this exits
# 0 may the database be destroyed.
#
# Usage: backup-via-ssm.sh <instance-id> <promote: true|false> <timeout-seconds> <backup-s3-uri>
# Exit:  0 dump uploaded and verified | 1 anything else
set -uo pipefail

INSTANCE_ID="${1:?instance id}"
PROMOTE="${2:?promote true|false}"
TIMEOUT="${3:-7200}"
BACKUP_URI="${4:?backup s3 uri}"
REGION="${AWS_REGION:-us-east-1}"
POLL=15

echo "Backing up participant DB $INSTANCE_ID (promote=$PROMOTE, timeout ${TIMEOUT}s)"

PARAMS=$(jq -cn --arg cmd "/usr/local/bin/participant-db-backup $PROMOTE" --arg t "$TIMEOUT" \
           '{commands: [$cmd], executionTimeout: [$t]}')
CMD_ID=$(aws ssm send-command --instance-ids "$INSTANCE_ID" --region "$REGION" \
           --document-name AWS-RunShellScript --comment "participant-db backup" \
           --timeout-seconds 600 --parameters "$PARAMS" \
           --query Command.CommandId --output text) || { echo "ERROR: ssm send-command failed"; exit 1; }
echo "SSM command $CMD_ID"

START=$(date +%s)
STATUS=Pending
while :; do
    STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
               --region "$REGION" --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in
        Pending|InProgress|Delayed) ;;
        *) break ;;
    esac
    if (( $(date +%s) - START >= TIMEOUT + 300 )); then
        echo "ERROR: gave up waiting for the backup command (last status $STATUS)"
        exit 1
    fi
    sleep "$POLL"
done

# SSM keeps only the tail of long output (the verbose pg_dump log), which is where the
# result line is.
OUT=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --region "$REGION" --query StandardOutputContent --output text 2>/dev/null)
ERR=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --region "$REGION" --query StandardErrorContent --output text 2>/dev/null)
echo "----- backup stdout (tail) -----"; tail -40 <<<"$OUT"
echo "----- backup stderr (tail) -----"; tail -40 <<<"$ERR"
echo "--------------------------------"

if [[ "$STATUS" != "Success" ]]; then
    echo "ERROR: backup command ended $STATUS"
    exit 1
fi

FILE=$(sed -n 's/^BACKUP_RESULT file=\([^ ]*\) .*/\1/p' <<<"$OUT" | tail -1)
BYTES=$(sed -n 's/^BACKUP_RESULT .*bytes=\([0-9]*\) .*/\1/p' <<<"$OUT" | tail -1)
if [[ -z "$FILE" || -z "$BYTES" ]]; then
    echo "ERROR: backup reported success but printed no BACKUP_RESULT line"
    exit 1
fi

BUCKET="${BACKUP_URI#s3://}"; BUCKET="${BUCKET%%/*}"
PREFIX="${BACKUP_URI#s3://$BUCKET/}"; PREFIX="${PREFIX%/}"
REMOTE=$(aws s3api head-object --bucket "$BUCKET" --key "$PREFIX/$FILE" --region "$REGION" \
           --query ContentLength --output text 2>/dev/null || echo missing)
if [[ "$REMOTE" != "$BYTES" ]]; then
    echo "ERROR: s3://$BUCKET/$PREFIX/$FILE is $REMOTE bytes, the instance reported $BYTES"
    exit 1
fi

if [[ "$PROMOTE" == "true" ]]; then
    LATEST=$(aws s3 cp "s3://$BUCKET/$PREFIX/LATEST" - --region "$REGION" 2>/dev/null | tr -d '[:space:]')
    if [[ "$LATEST" != "$FILE" ]]; then
        echo "ERROR: LATEST is '$LATEST', expected '$FILE'"
        exit 1
    fi
fi

echo "Verified s3://$BUCKET/$PREFIX/$FILE ($BYTES bytes)$([[ "$PROMOTE" == true ]] && echo '; LATEST points at it')"
