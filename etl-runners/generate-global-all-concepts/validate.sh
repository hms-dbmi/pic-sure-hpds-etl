#!/bin/bash
# Post-run validation for generate-global-all-concepts.
# Checks the JSON report for expected fields and non-zero row counts.
#
# Exit codes:
#   0  = pass
#   10 = warnings (e.g. empty abbreviations were skipped)
#   1  = hard failure
set -euo pipefail

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"

REPORT="$REPORTS_DIR/generate-global-all-concepts-${RUN_ID}.json"

if [[ ! -f "$REPORT" ]]; then
    echo "FAIL: report not found: $REPORT"
    exit 1
fi

echo "--- Report: $REPORT ---"
jq . "$REPORT"

STATUS=$(jq -r '.status' "$REPORT")
if [[ "$STATUS" != "SUCCESS" && "$STATUS" != "SUCCESS_WITH_WARNINGS" ]]; then
    echo "FAIL: job status is $STATUS"
    exit 1
fi

TOTAL_ROWS=$(jq -r '.metrics.totalRows // 0' "$REPORT")
if [[ "$TOTAL_ROWS" -le 0 ]]; then
    echo "FAIL: totalRows is $TOTAL_ROWS — expected at least 1"
    exit 1
fi

STUDIES_PROCESSED=$(jq -r '.metrics.studiesProcessed // 0' "$REPORT")
echo "Studies processed: $STUDIES_PROCESSED"
echo "Total rows: $TOTAL_ROWS"

# Check for warnings in output validation
WARNINGS=$(jq -r '.outputValidation.counts.warning // 0' "$REPORT")
if [[ "$WARNINGS" -gt 0 ]]; then
    echo "WARNING: $WARNINGS warning(s) in output validation (e.g. empty consent_abbreviation)"
    jq -r '.outputValidation.issues[] | select(.severity == "WARNING") | "  \(.code): \(.message)"' "$REPORT"
    exit 10
fi

echo "PASS: $TOTAL_ROWS rows across $STUDIES_PROCESSED studies"
exit 0
