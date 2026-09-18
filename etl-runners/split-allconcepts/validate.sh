#!/bin/bash
#
# Post-run validation for split-allconcepts, over the artifacts fetched from the
# runner's S3 report prefix.
#
# A split-allconcepts run is expected to leave, in the reports directory:
#   split-allconcepts-<runId>.json     the JobResult
#   container-status.json              what was actually invoked
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

REPORT="$REPORTS/split-allconcepts-$RUN_ID.json"

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
echo " split-allconcepts validation (run $RUN_ID)"
echo "=========================================================="

fold_generic "standard report checks" "$REPORT"

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

# --- job-specific metrics ----------------------------------------------------
STUDY_ID=$(jq -r '.metrics.studyId // "unknown"' "$REPORT")
TOTAL_ROWS=$(jq -r '.metrics.totalRows // 0' "$REPORT")
UNMAPPED=$(jq -r '.metrics.unmappedIds // 0' "$REPORT")
NO_CONSENT=$(jq -r '.metrics.noConsentRows // 0' "$REPORT")

echo ""
echo "  Study:          $STUDY_ID"
echo "  Total rows:     $TOTAL_ROWS"
echo "  Unmapped ids:   $UNMAPPED"
echo "  No-consent:     $NO_CONSENT"

check "at least one row was read"          test "$TOTAL_ROWS" -gt 0
soft  "no unmapped hpds ids"               test "$UNMAPPED" -eq 0
soft  "no rows without consent assignment" test "$NO_CONSENT" -eq 0

CONSENT_COUNT=$(jq -r '.metrics.rowsPerConsent | length' "$REPORT" 2>/dev/null || echo 0)
check "at least one consent file produced" test "$CONSENT_COUNT" -gt 0

echo ""
echo "  Consent breakdown:"
jq -r '.metrics.rowsPerConsent // {} | to_entries[] | "    c\(.key): \(.value) row(s)"' "$REPORT" 2>/dev/null || true

summary
