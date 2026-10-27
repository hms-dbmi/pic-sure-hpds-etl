#!/bin/bash
#
# Post-run validation for adapt-allconcepts-csvloader, over the artifacts fetched from the runner's S3
# report prefix:
#   adapt-allconcepts-csvloader-<runId>.json          the JobResult
#   adapt-allconcepts-csvloader-<runId>-report.json   the run's own report (also written for a failed run)
#
# Runs the standard report checks, then asserts that the row accounting balances and that the report
# and the JobResult agree.
#
# Usage: validate.sh <reports-dir> <run-id>
# Exit:  0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REPORTS="${1:-}"
RUN_ID="${2:-}"
if [[ -z "$REPORTS" || -z "$RUN_ID" ]]; then
  echo "usage: $0 <reports-dir> <run-id>" >&2
  exit 64
fi

REPORT="$REPORTS/adapt-allconcepts-csvloader-$RUN_ID.json"
OWN="$REPORTS/adapt-allconcepts-csvloader-$RUN_ID-report.json"

echo "=========================================================="
echo " adapt-allconcepts-csvloader validation (run $RUN_ID)"
echo "=========================================================="

"$HERE/../common/validate-report.sh" "$REPORT"
case $? in
  0)  echo "  [ok]   standard report checks"; _PASS=$((_PASS + 1)) ;;
  10) echo "  [warn] standard report checks (warnings above)"; _WARN=$((_WARN + 1)) ;;
  *)  echo "  [FAIL] standard report checks"; _FAIL=$((_FAIL + 1)) ;;
esac

if [[ ! -f "$REPORT" ]] || [[ "$(jq -r '.metrics.rowsInput // "absent"' "$REPORT")" == "absent" ]]; then
  if [[ -f "$OWN" ]]; then
    note "the run failed; its own report says:"
    jq -r '(.failures // [])[] | "         \(.)"' "$OWN"
    jq -r '"         rejects by code: \(.rejects.byCode | tojson)"' "$OWN"
  fi
  summary
  exit 1
fi

m() { jq -r ".metrics.$1 // 0" "$REPORT"; }
INPUT=$(m rowsInput)
COPIES=$(m rowsGlobalCopies)
WRITTEN=$(m rowsWritten)
REJECTED=$(m rowsRejected)
DROPPED=$(m rowsDropped)
PARTS=$(m partitions)

note "partitions: $PARTS; rows read: $INPUT; written: $WRITTEN; rejected: $REJECTED; dropped: $DROPPED; global copies: $COPIES"
note "consent codes seen: $(jq -c '.metrics.consentCodesSeen // []' "$REPORT"); multi-study patients: $(m multiStudyPatients)"

check "at least one partition was written" test "$PARTS" -gt 0
check "balanced: input ($INPUT) + globalCopies ($COPIES) = written ($WRITTEN) + rejected ($REJECTED) + dropped ($DROPPED)" \
  test "$((INPUT + COPIES))" -eq "$((WRITTEN + REJECTED + DROPPED))"
check "the job reports balanced=true" jq -e '.metrics.balanced == true' "$REPORT"

if [[ -f "$OWN" ]]; then
  check "the run's own report says ok" jq -e '.status == "ok"' "$OWN"
  check "its rows agree with the JobResult" jq -e --argjson w "$WRITTEN" --argjson i "$INPUT" '.rows.written == $w and .rows.input == $i' "$OWN"
  check "its partitions all have rows > 0" jq -e '[.partitions[] | select(.rows <= 0)] | length == 0' "$OWN"
else
  warn "the run's own report ($(basename "$OWN")) was not fetched"
fi

summary
