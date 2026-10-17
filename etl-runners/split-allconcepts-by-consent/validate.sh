#!/bin/bash
#
# Post-run validation for split-allconcepts-by-consent, over the artifacts fetched from the
# runner's S3 report prefix.
#
# A run is expected to leave, in the reports directory:
#   split-allconcepts-by-consent-<runId>.json   the JobResult
#   container-status.json                       what was actually invoked
#
# Usage: validate.sh <reports-dir> <run-id>
# Exit:  0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
# shellcheck disable=SC1091
source "$HERE/../common/lib.sh"

REPORTS="${1:-}"
RUN_ID="${2:-}"
if [[ -z "$REPORTS" || -z "$RUN_ID" ]]; then
  echo "usage: $0 <reports-dir> <run-id>" >&2
  exit 64
fi

REPORT="$REPORTS/split-allconcepts-by-consent-$RUN_ID.json"

# Folds a validate-report.sh run into this script's tally (0 clean / 10 warn / other fail).
fold_generic() {
  local label="$1" file="$2" rc
  "$HERE/../common/validate-report.sh" "$file"
  rc=$?
  case "$rc" in
    0)  echo "  [ok]   $label"; _PASS=$((_PASS + 1)) ;;
    10) echo "  [warn] $label (warnings above)"; _WARN=$((_WARN + 1)) ;;
    *)  echo "  [FAIL] $label"; _FAIL=$((_FAIL + 1)) ;;
  esac
}

echo "=========================================================="
echo " split-allconcepts-by-consent validation (run $RUN_ID)"
echo "=========================================================="

fold_generic "standard report checks" "$REPORT"

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

# --- job-specific metrics ----------------------------------------------------
TOTAL=$(jq -r '.metrics.totalRows // 0' "$REPORT")
STUDY=$(jq -r '.metrics.studyRows // 0' "$REPORT")
GLOBAL=$(jq -r '.metrics.globalRows // 0' "$REPORT")
DROP_STUDY=$(jq -r '.metrics.droppedStudyNoConsent // 0' "$REPORT")
DROP_GLOBAL=$(jq -r '.metrics.droppedGlobalNoConsent // 0' "$REPORT")
REJECTED=$(jq -r '.metrics.rejectedRows // 0' "$REPORT")
PARTITIONS=$(jq -r '.metrics.partitions // 0' "$REPORT")
STUDY_PARTS=$(jq -r '.metrics.studyPartitions // 0' "$REPORT")
GLOBAL_PARTS=$(jq -r '.metrics.globalPartitions // 0' "$REPORT")
LINES=$(jq -r '.metrics.partitionLines // 0' "$REPORT")
SECONDS_LIKE=$(jq -r '.metrics.timestampLikelySeconds // 0' "$REPORT")
MODE=$(jq -r '.metrics.globals // "unknown"' "$REPORT")
OUTPUT=$(jq -r '.metrics.outputUri // "unknown"' "$REPORT")
SUM=$(( STUDY + GLOBAL + DROP_STUDY + DROP_GLOBAL + REJECTED ))

echo ""
echo "  Output:             $OUTPUT"
echo "  Globals mode:       $MODE"
echo "  Input rows:         $TOTAL"
echo "  Study rows:         $STUDY   (to $STUDY_PARTS study partition(s))"
echo "  Global rows:        $GLOBAL   ($GLOBAL_PARTS global partition(s))"
echo "  Dropped (no consent): study $DROP_STUDY, global $DROP_GLOBAL"
echo "  Rejected (quarantined): $REJECTED"
echo "  Lines written:      $LINES"

check "at least one row was read"                    test "$TOTAL" -gt 0
check "at least one partition was written"           test "$PARTITIONS" -gt 0
check "input rows == study + global + dropped + rejected ($SUM)" test "$TOTAL" -eq "$SUM"
check "the job's own row accounting balanced"        jq -e '.metrics.balanced == true' "$REPORT"
check "manifest was written"                         jq -e '(.metrics.manifestUri // "") != ""' "$REPORT"
soft  "no study rows dropped for lack of a consent"  test "$DROP_STUDY" -eq 0
soft  "no global rows dropped for lack of a consent" test "$DROP_GLOBAL" -eq 0
soft  "no rows quarantined"                          test "$REJECTED" -eq 0
soft  "no timestamps that look like epoch seconds"   test "$SECONDS_LIKE" -eq 0

if [[ "$MODE" == "own" && "$GLOBAL_PARTS" -gt 0 ]]; then
  note "globals=own: $GLOBAL_PARTS global partition(s) load but stay unreachable until PSAMA grants those exact names"
fi

echo ""
echo "  Cross-check and data warnings:"
jq -r '[.outputValidation.issues[]? | select(.severity == "WARNING")] | .[] | "    \(.code): \(.message)"' "$REPORT" 2>/dev/null | cut -c1-400 || true

summary
