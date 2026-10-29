#!/bin/bash
#
# Writes study-result.json: one study's outcome in the migration's Split AllConcepts stage, whether
# it ran on a runner-pool worker (run-pooled-study.sh), on its own instance through a
# split-allconcepts build (large studies), or never ran (pre-flight failure, not started).
#
# It combines everything needed to debug the study without opening another page:
#   - the runner's status.json sentinel (exit code, phase, instance, runner start/finish)
#   - the job's own JobResult report, split-allconcepts-<run-id>.json (metrics, errorMessage, issues)
#   - the post-run validation (validate.sh) output: every failed check and warning
#   - where to look next: runner log, reports, worker log, build URL, and how to re-run the study
#
# split-report.sh consolidates these files into the stage's single report.
#
# Usage:
#   record-study-result.sh --out <file> --study <id> --abv <abbreviation> --lane <pool|large|preflight>
#       [--run-id <id>] [--attempts <n>] [--job-exit <code>] [--reports-dir <dir>]
#       [--validation-rc <rc>] [--validation-file <file>] [--input-uri <uri>] [--input-bytes <n>]
#       [--worker <instance-id>] [--worker-log <uri>] [--build-url <url>] [--log-uri <uri>]
#       [--reports-uri <uri>] [--started-epoch <s>] [--finished-epoch <s>]
#       [--stage <preflight|dispatch|run|validation|not-started>] [--reason <text>]
#
#   --stage/--reason record an outcome the caller already knows (a study that never ran, or failed
#   before the runner was involved). Otherwise the outcome is derived: --job-exit (or, when absent,
#   the exitCode in <reports-dir>/status.json) and then --validation-rc.
#
# Prints the outcome (SUCCESS, SUCCESS_WITH_WARNINGS, FAILED or NOT_STARTED).
# Exit: 0 written | 64 usage | 1 could not write the file
set -uo pipefail

usage() { echo "usage: $0 --out <file> --study <id> --abv <abv> --lane <lane> [options]; see the header" >&2; exit 64; }

OUT="" STUDY="" ABV="" LANE="" RUN_ID="" ATTEMPTS=0 JOB_EXIT="" REPORTS_DIR="" VALIDATION_RC=""
VALIDATION_FILE="" INPUT_URI="" INPUT_BYTES="" WORKER="" WORKER_LOG="" BUILD_URL="" LOG_URI=""
REPORTS_URI="" STARTED="" FINISHED="" STAGE="" REASON=""
while [[ $# -gt 0 ]]; do
  [[ $# -ge 2 ]] || usage
  case "$1" in
    --out) OUT="$2" ;;               --study) STUDY="$2" ;;          --abv) ABV="$2" ;;
    --lane) LANE="$2" ;;             --run-id) RUN_ID="$2" ;;        --attempts) ATTEMPTS="$2" ;;
    --job-exit) JOB_EXIT="$2" ;;     --reports-dir) REPORTS_DIR="$2" ;;
    --validation-rc) VALIDATION_RC="$2" ;;  --validation-file) VALIDATION_FILE="$2" ;;
    --input-uri) INPUT_URI="$2" ;;   --input-bytes) INPUT_BYTES="$2" ;;
    --worker) WORKER="$2" ;;         --worker-log) WORKER_LOG="$2" ;; --build-url) BUILD_URL="$2" ;;
    --log-uri) LOG_URI="$2" ;;       --reports-uri) REPORTS_URI="$2" ;;
    --started-epoch) STARTED="$2" ;; --finished-epoch) FINISHED="$2" ;;
    --stage) STAGE="$2" ;;           --reason) REASON="$2" ;;
    *) usage ;;
  esac
  shift 2
done
[[ -n "$OUT" && -n "$STUDY" && -n "$LANE" ]] || usage
[[ "$ATTEMPTS" =~ ^[0-9]+$ ]] || ATTEMPTS=0
[[ "$INPUT_BYTES" =~ ^[0-9]+$ ]] || INPUT_BYTES=""

exit_name() {
  case "$1" in
    0) echo SUCCESS ;; 2) echo VALIDATION_FAILED ;; 3) echo DATA_ERROR ;;
    4) echo INFRASTRUCTURE_ERROR ;; 5) echo CONFIG_ERROR ;; 124) echo TIMEOUT ;;
    "") echo "" ;; *) echo UNKNOWN ;;
  esac
}

# --- what the runner and the job left behind ---------------------------------------------------
STATUS_FILE=""
[[ -n "$REPORTS_DIR" && -f "$REPORTS_DIR/status.json" ]] && STATUS_FILE="$REPORTS_DIR/status.json"
# The run id actually used (a retry adds -r2) is the one in the sentinel.
if [[ -n "$STATUS_FILE" ]]; then
  sentinel_run=$(jq -r '.runId // empty' "$STATUS_FILE" 2>/dev/null)
  [[ -n "$sentinel_run" ]] && RUN_ID="$sentinel_run"
  [[ -z "$JOB_EXIT" ]] && JOB_EXIT=$(jq -r '.exitCode // empty' "$STATUS_FILE" 2>/dev/null)
fi
REPORT=""
if [[ -n "$REPORTS_DIR" && -n "$RUN_ID" && -f "$REPORTS_DIR/split-allconcepts-$RUN_ID.json" ]]; then
  REPORT="$REPORTS_DIR/split-allconcepts-$RUN_ID.json"
fi
[[ "$JOB_EXIT" =~ ^[0-9]+$ ]] || JOB_EXIT=""

job_json=null
if [[ -n "$REPORT" ]]; then
  job_json=$(jq -c '{
      exitCode, status, errorMessage, durationMillis,
      metrics: (.metrics // {}),
      issues: [ (.inputValidation.issues // [])[], (.outputValidation.issues // [])[]
                | select(.severity == "ERROR" or .severity == "WARNING")
                | {severity, code, message, location} ]
    }' "$REPORT" 2>/dev/null) || job_json='{"unreadable": true}'
fi
runner_json=null
if [[ -n "$STATUS_FILE" ]]; then
  runner_json=$(jq -c '{phase, exitCode, exitName, instanceId, startedAt, finishedAt}' "$STATUS_FILE" 2>/dev/null) || runner_json=null
fi

lines_matching() {   # the labels of validate.sh lines tagged $1, as a JSON array
  if [[ -n "$VALIDATION_FILE" && -f "$VALIDATION_FILE" ]]; then
    grep -F -- "$1" "$VALIDATION_FILE" | while IFS= read -r line; do
      line="${line#*"$1"}"
      printf '%s\n' "${line#"${line%%[![:space:]]*}"}"
    done | jq -R -s -c 'split("\n") | map(select(length > 0))'
  else
    echo '[]'
  fi
}
failed_checks=$(lines_matching '[FAIL]')
# validate.sh follows the report's own [warn] lines with "standard report checks (warnings above)";
# that line only points at the others, so it is not listed as a warning of its own.
warnings=$(lines_matching '[warn]' | jq -c 'map(select(endswith("(warnings above)") | not))')

# --- outcome -----------------------------------------------------------------------------------
JOB_ERROR=""
[[ "$job_json" != null ]] && JOB_ERROR=$(jq -r '.errorMessage // empty' <<<"$job_json")

if [[ -n "$STAGE" ]]; then
  OUTCOME=FAILED
  [[ "$STAGE" == not-started ]] && OUTCOME=NOT_STARTED
  [[ -n "$REASON" ]] || REASON="$STAGE"
elif [[ -z "$JOB_EXIT" ]]; then
  OUTCOME=FAILED; STAGE=run
  [[ -n "$REASON" ]] || REASON="no exit code was recorded for the run (no status.json); see the runner log"
elif [[ "$JOB_EXIT" == 0 ]]; then
  case "$VALIDATION_RC" in
    0)  OUTCOME=SUCCESS ;;
    10) OUTCOME=SUCCESS_WITH_WARNINGS ;;
    "") OUTCOME=FAILED; STAGE=validation
        [[ -n "$REASON" ]] || REASON="the job exited 0 but its output was never validated" ;;
    *)  OUTCOME=FAILED; STAGE=validation
        if [[ -z "$REASON" ]]; then
          REASON="the job reported success but its output failed validation"
          checks=$(jq -r 'join("; ")' <<<"$failed_checks")
          [[ -n "$checks" ]] && REASON="$REASON: $checks"
        fi ;;
  esac
else
  OUTCOME=FAILED; STAGE=run
  if [[ -z "$REASON" ]]; then
    REASON="$(exit_name "$JOB_EXIT") (exit $JOB_EXIT)"
    if [[ -n "$JOB_ERROR" ]]; then
      REASON="$REASON: $JOB_ERROR"
    elif [[ "$JOB_EXIT" == 124 ]]; then
      REASON="$REASON: timed out waiting for the runner to finish"
    elif [[ "$JOB_EXIT" == 4 ]]; then
      REASON="$REASON: the runner died or lost contact before the job wrote a report"
    elif [[ -z "$REPORT" ]]; then
      REASON="$REASON: the job wrote no report"
    fi
  fi
fi
[[ "$OUTCOME" == FAILED || "$OUTCOME" == NOT_STARTED ]] || STAGE=""
[[ "$OUTCOME" == FAILED || "$OUTCOME" == NOT_STARTED ]] || REASON=""

ARTIFACTS_DIR=$(dirname "$OUT")
mkdir -p "$ARTIFACTS_DIR" || exit 1

jq -n \
  --arg study "$STUDY" --arg abv "$ABV" --arg lane "$LANE" --arg outcome "$OUTCOME" \
  --arg stage "$STAGE" --arg reason "$REASON" --arg run "$RUN_ID" --argjson attempts "$ATTEMPTS" \
  --arg exit "$JOB_EXIT" --arg exitName "$(exit_name "$JOB_EXIT")" \
  --arg input "$INPUT_URI" --arg bytes "$INPUT_BYTES" \
  --arg worker "$WORKER" --arg workerLog "$WORKER_LOG" --arg build "$BUILD_URL" \
  --arg log "$LOG_URI" --arg reports "$REPORTS_URI" --arg artifacts "$ARTIFACTS_DIR" \
  --arg started "$STARTED" --arg finished "$FINISHED" --arg vrc "$VALIDATION_RC" \
  --argjson job "$job_json" --argjson runner "$runner_json" \
  --argjson failed "$failed_checks" --argjson warnings "$warnings" \
  'def opt: if . == "" then null else . end;
   def num: if . == "" then null else tonumber end;
   {
     studyId: $study, abbreviation: ($abv | opt), lane: $lane, outcome: $outcome,
     failureStage: ($stage | opt), failureReason: ($reason | opt),
     exitCode: ($exit | num), exitName: ($exitName | opt),
     runId: ($run | opt), attempts: $attempts,
     inputUri: ($input | opt), inputBytes: ($bytes | num),
     startedAt: ($started | num | if . == null then null else todate end),
     finishedAt: ($finished | num | if . == null then null else todate end),
     durationMillis: (if $started != "" and $finished != "" then (($finished | tonumber) - ($started | tonumber)) * 1000 else null end),
     runner: $runner,
     job: $job,
     validation: { rc: ($vrc | num), failedChecks: $failed, warnings: $warnings },
     debug: {
       runnerLog: ($log | opt), reports: ($reports | opt), artifacts: $artifacts,
       worker: ($worker | opt), workerLog: ($workerLog | opt), build: ($build | opt),
       rerun: ("Re-run only this study: STUDY_FILTER=" + $study)
     }
   }' > "$OUT.tmp" && mv "$OUT.tmp" "$OUT" || { rm -f "$OUT.tmp"; echo "record-study-result: could not write $OUT" >&2; exit 1; }

echo "$OUTCOME"
