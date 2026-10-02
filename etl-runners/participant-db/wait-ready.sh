#!/bin/bash
#
# Waits for a freshly provisioned participant DB to finish booting. The instance uploads
# status.json last ({"status": "ready"|"failed", ...}); until then this polls it, and fails
# early if the instance itself stops or terminates. On failure, prints the boot log tail.
#
# Usage: wait-ready.sh <instance-id> <status-s3-uri> <log-s3-uri> [timeout-seconds]
# Exit:  0 ready | 1 failed | 124 timed out
set -uo pipefail

INSTANCE_ID="${1:?instance id}"
STATUS_URI="${2:?status uri}"
LOG_URI="${3:?log uri}"
TIMEOUT="${4:-3600}"
REGION="${AWS_REGION:-us-east-1}"
POLL=20

show_log() {
    echo "----- boot log tail ($LOG_URI) -----"
    aws s3 cp "$LOG_URI" - --region "$REGION" 2>/dev/null | tail -60 || echo "(no log uploaded)"
    echo "------------------------------------"
}

echo "Waiting for participant DB $INSTANCE_ID (timeout ${TIMEOUT}s)"
START=$(date +%s)
while :; do
    if STATUS_JSON=$(aws s3 cp "$STATUS_URI" - --region "$REGION" 2>/dev/null); then
        echo "$STATUS_JSON"
        if [[ "$(jq -r '.status' <<<"$STATUS_JSON")" == "ready" ]]; then
            echo "Participant DB is ready (restored from: $(jq -r '.restoredFrom | if . == "" then "<fresh schema>" else . end' <<<"$STATUS_JSON"))"
            exit 0
        fi
        echo "ERROR: participant DB boot failed in phase '$(jq -r '.phase' <<<"$STATUS_JSON")'"
        show_log
        exit 1
    fi

    STATE=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --region "$REGION" \
              --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo unknown)
    case "$STATE" in
        stopping|stopped|shutting-down|terminated)
            echo "ERROR: instance is $STATE before reporting ready"
            show_log
            exit 1 ;;
    esac

    ELAPSED=$(( $(date +%s) - START ))
    if (( ELAPSED >= TIMEOUT )); then
        echo "ERROR: timed out after ${ELAPSED}s (instance state: $STATE)"
        show_log
        exit 124
    fi
    echo "  ... ${ELAPSED}s, instance $STATE, not ready yet"
    sleep "$POLL"
done
