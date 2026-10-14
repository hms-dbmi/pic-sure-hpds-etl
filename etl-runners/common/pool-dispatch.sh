#!/bin/bash
#
# Hands one job run to a runner-pool worker (etl-runners/runner-pool) through its S3 mailbox, then
# prints where that run will publish, for monitor-runner.sh and fetch-reports.
#
# The worker publishes the run exactly as a single runner would: reports and the status.json
# sentinel under etl-runner/reports/<module>/<run-id>/, the log at
# etl-runner/logs/<module>-<run-id>.log. So the caller monitors and validates it unchanged.
#
# Usage:
#   pool-dispatch.sh <instance-id> <mailbox-s3-prefix> <module> <job> <run-id> [param=value ...]
#
#   instance-id        the worker, e.g. i-0123456789abcdef0
#   mailbox-s3-prefix  the pool's mailbox root (runner-pool output mailbox_s3_prefix)
#   module             reports/log naming, e.g. split-allconcepts
#   job, run-id        --job and --run-id for the container
#   param=value        job parameters: study_id=phs000001 becomes --study-id=phs000001
#
# Prints (stdout, one per line): STATUS_URI=..., REPORTS_URI=..., LOG_URI=...
#
# Exit: 0 dispatched | 1 refused (bad input, the worker already has a pending task, or S3 failed)
set -uo pipefail

INSTANCE_ID="${1:-}"
MAILBOX="${2:-}"
MODULE="${3:-}"
JOB="${4:-}"
RUN_ID="${5:-}"
shift 5 2>/dev/null || true
REGION="${AWS_REGION:-us-east-1}"

die() { echo "pool-dispatch: $*" >&2; exit 1; }

[[ "$INSTANCE_ID" =~ ^i-[0-9a-f]+$ ]] || die "instance id must look like i-0123abcd, got '$INSTANCE_ID'"
[[ "$MAILBOX" =~ ^s3://[a-z0-9.-]+/[A-Za-z0-9._/-]+$ && "$MAILBOX" != *..* ]] \
    || die "mailbox must be an s3://bucket/prefix URI, got '$MAILBOX'"
for v in "$MODULE" "$JOB" "$RUN_ID"; do
  [[ "$v" =~ ^[A-Za-z0-9._-]+$ ]] || die "module, job and run id may contain only letters, digits, '.', '_' and '-' (got '$v')"
done

MAILBOX="${MAILBOX%/}"
BUCKET="${MAILBOX#s3://}"
BUCKET="${BUCKET%%/*}"
REPORTS_PREFIX="etl-runner/reports/$MODULE/$RUN_ID"
LOG_KEY="etl-runner/logs/$MODULE-$RUN_ID.log"
TASK_URI="$MAILBOX/$INSTANCE_ID/task.json"

# Job parameters become ETL_PARAM_<key> for the container's run-job.sh. Values arrive through a
# podman --env-file on the worker, so they must be single-line; the worker re-checks this.
env_json=$(jq -n --arg job "$JOB" --arg run "$RUN_ID" '{ETL_JOB: $job, ETL_RUN_ID: $run}')
for kv in "$@"; do
  [[ "$kv" == *=* ]] || die "parameter must be key=value, got '$kv'"
  key="${kv%%=*}"
  value="${kv#*=}"
  [[ "$key" =~ ^[A-Za-z0-9_-]+$ ]] || die "parameter name '$key' may contain only letters, digits, '_' and '-'"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "parameter '$key' contains a line break"
  env_json=$(jq --arg k "ETL_PARAM_${key//-/_}" --arg v "$value" '. + {($k): $v}' <<<"$env_json")
done

# One task at a time per worker: a task still in the mailbox means the worker has not picked up
# the previous one, and overwriting it would silently drop a run.
if aws s3 ls "$TASK_URI" --region "$REGION" >/dev/null 2>&1; then
  die "worker $INSTANCE_ID still has an unclaimed task at $TASK_URI"
fi

# A sentinel left by an earlier attempt with the same run id would read as this run's result.
aws s3 rm "s3://$BUCKET/$REPORTS_PREFIX/status.json" --region "$REGION" --only-show-errors >/dev/null 2>&1 || true

task=$(jq -n --arg run "$RUN_ID" --arg reports "$REPORTS_PREFIX" --arg log "$LOG_KEY" \
             --argjson env "$env_json" \
             '{runId: $run, reportsPrefix: $reports, logKey: $log, env: $env}')
printf '%s\n' "$task" | aws s3 cp - "$TASK_URI" --region "$REGION" --only-show-errors \
    || die "could not write $TASK_URI"

echo "pool-dispatch: run $RUN_ID handed to worker $INSTANCE_ID" >&2
echo "STATUS_URI=s3://$BUCKET/$REPORTS_PREFIX/status.json"
echo "REPORTS_URI=s3://$BUCKET/$REPORTS_PREFIX/"
echo "LOG_URI=s3://$BUCKET/$LOG_KEY"
