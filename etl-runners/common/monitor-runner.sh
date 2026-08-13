#!/bin/bash
#
# Waits for an ephemeral hpds-etl runner to finish and exits with the JOB'S exit code, so
# the calling Jenkins stage can branch on the ETL contract (0 success, 2 validation,
# 3 data, 4 infrastructure/retryable, 5 config).
#
# Completion is decided by the status.json sentinel in S3, which the runner uploads last. The JAR
# reports a precise exit code, so there is no log prose to pattern-match and a run cannot be
# misread as successful because a phrase happened to appear.
#
# Usage:
#   monitor-runner.sh <instance-id> <status-s3-uri> [log-s3-uri]
#
# Environment:
#   AWS_REGION         default us-east-1
#   POLL_INTERVAL      seconds between checks (default 15)
#   PIPELINE_TIMEOUT   max seconds to wait for completion (default 7200)
#   BOOT_TIMEOUT       max seconds to wait for the instance to reach running (default 600)
#   GRACE_TIMEOUT      seconds to wait for the sentinel after the instance dies (default 180)
#
# Exit codes:
#   0|2|3|5   the job's own ExitCode, read from status.json
#   4         infrastructure: instance never booted, or died without publishing a status
#   124       timed out waiting for the run to finish (no automatic retry)
set -uo pipefail

INSTANCE_ID="${1:-}"
STATUS_URI="${2:-}"
LOG_URI="${3:-}"

REGION="${AWS_REGION:-us-east-1}"
POLL_INTERVAL="${POLL_INTERVAL:-15}"
PIPELINE_TIMEOUT="${PIPELINE_TIMEOUT:-7200}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-600}"
GRACE_TIMEOUT="${GRACE_TIMEOUT:-180}"

if [[ -z "$INSTANCE_ID" || -z "$STATUS_URI" ]]; then
  echo "usage: $0 <instance-id> <status-s3-uri> [log-s3-uri]" >&2
  exit 64
fi

log()  { echo "[monitor $(date -u +%H:%M:%S)] $*"; }
warn() { echo "[monitor $(date -u +%H:%M:%S)] WARN: $*" >&2; }

instance_state() {
  aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE_ID" \
    --query "Reservations[0].Instances[0].State.Name" --output text 2>/dev/null || echo unknown
}

sentinel_present() {
  aws s3 ls "$STATUS_URI" --region "$REGION" >/dev/null 2>&1
}

# Prints the run's status and returns the job's exit code.
report_sentinel() {
  local body
  body=$(aws s3 cp "$STATUS_URI" - --region "$REGION" 2>/dev/null) || {
    warn "sentinel present but could not be downloaded"
    return 4
  }

  echo "=== status.json ==="
  echo "$body"
  echo "==================="

  local code phase
  code=$(printf '%s' "$body" | jq -r '.exitCode // empty')
  phase=$(printf '%s' "$body" | jq -r '.phase // "unknown"')

  if [[ -z "$code" ]]; then
    warn "sentinel carried no exitCode"
    return 4
  fi
  log "Run finished in phase '$phase' with exit $code ($(printf '%s' "$body" | jq -r '.exitName // "?"'))"
  return "$code"
}

dump_log_from_s3() {
  [[ -z "$LOG_URI" ]] && return 0
  log "Fetching runner log: $LOG_URI"
  echo "=== runner log ==="
  aws s3 cp "$LOG_URI" - --region "$REGION" 2>/dev/null || warn "log not available in S3"
  echo "=== end log ==="
}

# Best-effort live tail through SSM, for the Jenkins console only. Never fatal: the sentinel is
# the source of truth.
LAST_TAIL_HASH=""
tail_via_ssm() {
  local cmd_id status content
  cmd_id=$(aws ssm send-command --region "$REGION" \
    --document-name "AWS-RunShellScript" \
    --instance-ids "$INSTANCE_ID" \
    --parameters '{"commands":["tail -40 /var/log/etl-pipeline.log 2>/dev/null || echo __NO_LOG_YET__"]}' \
    --query "Command.CommandId" --output text 2>/dev/null) || return 0

  for _ in $(seq 1 15); do
    status=$(aws ssm get-command-invocation --region "$REGION" \
      --command-id "$cmd_id" --instance-id "$INSTANCE_ID" \
      --query "Status" --output text 2>/dev/null || echo Pending)
    case "$status" in
      Success)
        content=$(aws ssm get-command-invocation --region "$REGION" \
          --command-id "$cmd_id" --instance-id "$INSTANCE_ID" \
          --query "StandardOutputContent" --output text 2>/dev/null)
        local hash
        hash=$(printf '%s' "$content" | cksum | cut -d' ' -f1)
        if [[ "$hash" != "$LAST_TAIL_HASH" ]]; then
          echo "--- runner log tail ---"
          printf '%s\n' "$content"
          echo "-----------------------"
          LAST_TAIL_HASH="$hash"
        fi
        return 0
        ;;
      Failed|Cancelled|TimedOut) return 0 ;;
    esac
    sleep 2
  done
  return 0
}

# --------------------------------------------------------------------------
log "Monitoring $INSTANCE_ID (sentinel: $STATUS_URI)"

# 1. Boot. A short-lived job can finish before 'running' is ever observed, so a present sentinel
#    always wins over the instance state.
boot_start=$(date +%s)
while true; do
  sentinel_present && break

  state=$(instance_state)
  [[ "$state" == "running" ]] && { log "Instance running"; break; }

  case "$state" in
    shutting-down|terminated|stopped|stopping)
      log "Instance already $state before reaching running"
      break
      ;;
  esac

  if (( $(date +%s) - boot_start >= BOOT_TIMEOUT )); then
    warn "Instance did not reach running within ${BOOT_TIMEOUT}s (state=$state)"
    exit 4
  fi
  log "Instance state: $state"
  sleep "$POLL_INTERVAL"
done

# 2. Run.
run_start=$(date +%s)
while true; do
  if sentinel_present; then
    report_sentinel
    rc=$?
    dump_log_from_s3
    exit "$rc"
  fi

  state=$(instance_state)
  case "$state" in
    shutting-down|terminated|stopped)
      log "Instance is $state; waiting up to ${GRACE_TIMEOUT}s for the status sentinel"
      grace_start=$(date +%s)
      while (( $(date +%s) - grace_start < GRACE_TIMEOUT )); do
        if sentinel_present; then
          report_sentinel
          rc=$?
          dump_log_from_s3
          exit "$rc"
        fi
        sleep 10
      done
      warn "Instance terminated without publishing a status sentinel"
      dump_log_from_s3
      # No sentinel: the bootstrap died before it could report.
      exit 4
      ;;
    running)
      tail_via_ssm
      ;;
    *)
      warn "Unexpected instance state: $state"
      ;;
  esac

  if (( $(date +%s) - run_start >= PIPELINE_TIMEOUT )); then
    warn "Timed out after ${PIPELINE_TIMEOUT}s waiting for the run to finish"
    dump_log_from_s3
    exit 124
  fi
  sleep "$POLL_INTERVAL"
done
