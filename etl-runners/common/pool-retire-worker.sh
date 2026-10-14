#!/bin/bash
#
# Takes a runner-pool worker out of service by terminating it. Used when a run on it timed out:
# the job may still be running there, and the worker would otherwise be handed the next task while
# the old one is still going -- that task's clock would start before it could, and every task after
# it would be refused as "unclaimed". Terminating also stops the abandoned job from publishing its
# output and status after its study was already reported as failed.
#
# Usage: pool-retire-worker.sh <instance-id> <reason>
# Exit:  0 terminated (or already gone) | 1 could not terminate it | 64 usage
set -uo pipefail

INSTANCE_ID="${1:-}"
REASON="${2:-}"
REGION="${AWS_REGION:-us-east-1}"

if [[ ! "$INSTANCE_ID" =~ ^i-[0-9a-f]+$ || -z "$REASON" ]]; then
  echo "usage: $0 <instance-id> <reason>" >&2
  exit 64
fi

echo "pool-retire-worker: terminating worker $INSTANCE_ID: $REASON" >&2
if err=$(aws ec2 terminate-instances --region "$REGION" --instance-ids "$INSTANCE_ID" 2>&1 >/dev/null); then
  exit 0
fi
# Already gone counts as retired.
if [[ "$err" == *InvalidInstanceID.NotFound* ]]; then
  exit 0
fi
echo "pool-retire-worker: could not terminate $INSTANCE_ID: $err" >&2
exit 1
