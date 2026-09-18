#!/bin/bash
# Post-run validation for all-concepts-data-generator.
# Checks the JSON report for expected fields, non-zero row counts, and per-consent output.
#
# Exit codes:
#   0  = pass
#   10 = warnings (e.g. unmapped patients)
#   1  = hard failure
set -euo pipefail

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"

REPORT="$REPORTS_DIR/all-concepts-data-generator-${RUN_ID}.json"

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

ROWS_PROCESSED=$(jq -r '.metrics.rowsProcessed // 0' "$REPORT")
if [[ "$ROWS_PROCESSED" -le 0 ]]; then
    echo "FAIL: rowsProcessed is $ROWS_PROCESSED — expected at least 1"
    exit 1
fi

CONSENT_GROUPS=$(jq -r '.metrics.consentGroups // 0' "$REPORT")
OUTPUT_FILES=$(jq -r '.metrics.outputFiles | length // 0' "$REPORT")

echo "Study: $(jq -r '.metrics.studyId' "$REPORT")"
echo "Consent groups: $CONSENT_GROUPS"
echo "Rows processed: $ROWS_PROCESSED"
echo "Output files: $OUTPUT_FILES"

# Per-consent breakdown
echo "--- Per-consent rows ---"
jq -r '.metrics.rowsPerConsent | to_entries[] | "  \(.key): \(.value) row(s)"' "$REPORT"

# Check for warnings in output validation
WARNINGS=$(jq -r '.outputValidation.counts.warning // 0' "$REPORT")
if [[ "$WARNINGS" -gt 0 ]]; then
    echo "WARNING: $WARNINGS warning(s) in output validation"
    jq -r '.outputValidation.issues[] | select(.severity == "WARNING") | "  \(.code): \(.message)"' "$REPORT"
    exit 10
fi

echo "PASS: $ROWS_PROCESSED rows across $CONSENT_GROUPS consent group(s)"
exit 0
