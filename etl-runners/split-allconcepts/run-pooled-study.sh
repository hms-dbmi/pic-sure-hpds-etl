#!/bin/bash
#
# Runs one study of the migration's Split AllConcepts stage on a runner-pool worker, end to end:
# hand it to the worker (../common/pool-dispatch.sh), wait for its status sentinel
# (../common/monitor-runner.sh), retry once on an infrastructure error while the worker is still
# running, fetch its reports, validate them (validate.sh), and record the outcome
# (record-study-result.sh) as <out-dir>/study-result.json.
#
# Everything about the study lands in <out-dir>, so a failed study can be debugged from the
# orchestrator's archived artifacts alone:
#   study-result.json        outcome, reason, metrics, failed checks, where to look next
#   monitor-attempt-<n>.log  the monitor's output for each attempt, including the full runner log
#   reports/                 the job's JobResult, container-status.json and status.json
#   validation.txt           validate.sh's output
#
# The console gets one prefixed line per step, so parallel studies stay readable, and the last
# 40 lines of the monitor output when an attempt fails.
#
# Usage: run-pooled-study.sh <out-dir>
#
# Environment (required):
#   STUDY_ID ABBREVIATION INPUT_URI MAPPING_URI OUTPUT_URI   the job's parameters
#   RUN_ID                                                   base run id (a retry appends -r2)
#   POOL_INSTANCE POOL_MAILBOX                               the worker and the pool's mailbox root
# Environment (optional):
#   INPUT_BYTES         recorded in the result
#   POOL_LOG_PREFIX     the pool's worker-log prefix (runner-pool output worker_log_s3_prefix)
#   RETRY_ON_INFRA      true (default) retries an exit 4 once while the worker is running
#   PIPELINE_TIMEOUT    seconds to wait per attempt (default 3600; passed to monitor-runner.sh)
#   RETRY_DELAY         seconds before a retry (default 30)
#   AWS_REGION          default us-east-1
#
# Exit: 0 succeeded | 10 succeeded with warnings | 1 failed (see study-result.json) | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="$HERE/../common"

OUT="${1:-}"
[[ -n "$OUT" ]] || { echo "usage: $0 <out-dir>" >&2; exit 64; }
for v in STUDY_ID INPUT_URI MAPPING_URI OUTPUT_URI RUN_ID POOL_INSTANCE POOL_MAILBOX; do
  [[ -n "${!v:-}" ]] || { echo "run-pooled-study: $v is required" >&2; exit 64; }
done
ABBREVIATION="${ABBREVIATION:-}"
RETRY_ON_INFRA="${RETRY_ON_INFRA:-true}"
RETRY_DELAY="${RETRY_DELAY:-30}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export PIPELINE_TIMEOUT="${PIPELINE_TIMEOUT:-3600}"

mkdir -p "$OUT"
rm -rf "$OUT/reports" "$OUT/validation.txt" "$OUT"/monitor-attempt-*.log
STARTED=$(date +%s)

say() { echo "[$STUDY_ID] $*"; }

worker_state() {
  aws ec2 describe-instances --region "$AWS_REGION" --instance-ids "$POOL_INSTANCE" \
    --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo unknown
}

worker_log=""
[[ -n "${POOL_LOG_PREFIX:-}" ]] && worker_log="$POOL_LOG_PREFIX-$POOL_INSTANCE.log"

record() {
  "$HERE/record-study-result.sh" --out "$OUT/study-result.json" --study "$STUDY_ID" --abv "$ABBREVIATION" \
    --lane pool --run-id "$run_id" --attempts "$attempt" --input-uri "$INPUT_URI" \
    --input-bytes "${INPUT_BYTES:-}" --worker "$POOL_INSTANCE" --worker-log "$worker_log" \
    --log-uri "$LOG_URI" --reports-uri "$REPORTS_URI" \
    --started-epoch "$STARTED" --finished-epoch "$(date +%s)" "$@"
}

# --- run, retrying once on an infrastructure error ----------------------------------------------
max_attempts=1
[[ "$RETRY_ON_INFRA" == true ]] && max_attempts=2
attempt=0 rc="" run_id="$RUN_ID" LOG_URI="" REPORTS_URI="" STATUS_URI=""
while (( attempt < max_attempts )); do
  attempt=$((attempt + 1))
  run_id="$RUN_ID"; (( attempt > 1 )) && run_id="$RUN_ID-r$attempt"

  say "attempt $attempt/$max_attempts: handing run $run_id to worker $POOL_INSTANCE"
  if ! handoff=$("$COMMON/pool-dispatch.sh" "$POOL_INSTANCE" "$POOL_MAILBOX" split-allconcepts split-allconcepts \
                   "$run_id" study_id="$STUDY_ID" abbreviation="$ABBREVIATION" input="$INPUT_URI" \
                   mapping="$MAPPING_URI" output="$OUTPUT_URI" 2>"$OUT/dispatch.err"); then
    reason="could not hand the study to worker $POOL_INSTANCE: $(tr '\n' ' ' < "$OUT/dispatch.err")"
    say "FAILED: $reason"
    outcome=$(record --stage dispatch --reason "$reason")
    exit 1
  fi
  STATUS_URI=$(sed -n 's/^STATUS_URI=//p' <<<"$handoff")
  REPORTS_URI=$(sed -n 's/^REPORTS_URI=//p' <<<"$handoff")
  LOG_URI=$(sed -n 's/^LOG_URI=//p' <<<"$handoff")

  say "waiting for the runner (status: $STATUS_URI)"
  "$COMMON/monitor-runner.sh" "$POOL_INSTANCE" "$STATUS_URI" "$LOG_URI" > "$OUT/monitor-attempt-$attempt.log" 2>&1
  rc=$?
  say "attempt $attempt finished with exit $rc"
  if (( rc != 0 )); then
    say "last lines of the monitor output (full: $OUT/monitor-attempt-$attempt.log):"
    tail -n 40 "$OUT/monitor-attempt-$attempt.log" | sed "s/^/[$STUDY_ID]   /"
  fi

  (( rc == 4 && attempt < max_attempts )) || break
  state=$(worker_state)
  if [[ "$state" != running ]]; then
    say "exit 4 and worker $POOL_INSTANCE is $state: not retrying${worker_log:+ (worker log: $worker_log)}"
    break
  fi
  say "exit 4 (INFRASTRUCTURE_ERROR) with the worker still running: retrying in ${RETRY_DELAY}s"
  sleep "$RETRY_DELAY"
done

# --- reports and validation --------------------------------------------------------------------
mkdir -p "$OUT/reports"
if ! aws s3 sync "$REPORTS_URI" "$OUT/reports" --region "$AWS_REGION" --no-progress --only-show-errors \
       > "$OUT/fetch.log" 2>&1; then
  say "WARN: could not fetch every report from $REPORTS_URI (see $OUT/fetch.log)"
fi

vrc=""
if [[ -f "$OUT/reports/split-allconcepts-$run_id.json" || "$rc" == 0 ]]; then
  "$HERE/validate.sh" "$OUT/reports" "$run_id" > "$OUT/validation.txt" 2>&1
  vrc=$?
fi

outcome=$(record --job-exit "$rc" --reports-dir "$OUT/reports" --validation-rc "$vrc" \
                 --validation-file "$OUT/validation.txt") || outcome=FAILED

reason=$(jq -r '.failureReason // empty' "$OUT/study-result.json" 2>/dev/null)
case "$outcome" in
  SUCCESS)               say "SUCCESS (run $run_id)"; exit 0 ;;
  SUCCESS_WITH_WARNINGS) say "SUCCESS WITH WARNINGS (run $run_id): $(jq -r '.validation.warnings | join("; ")' "$OUT/study-result.json")"; exit 10 ;;
  *)                     say "FAILED (run $run_id): ${reason:-see $OUT/study-result.json}"
                         say "  runner log: ${LOG_URI:-unknown}${worker_log:+; worker log: $worker_log}"
                         exit 1 ;;
esac
