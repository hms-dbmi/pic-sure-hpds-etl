#!/bin/bash
# Post-run validation for merge-allconcepts.
# Checks the JSON report for expected fields.
#
# Exit codes:
#   0  = pass
#   10 = warnings
#   1  = hard failure
set -euo pipefail

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"

REPORT="$REPORTS_DIR/merge-allconcepts-${RUN_ID}.json"

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

FOLDERS_MERGED=$(jq -r '.metrics.foldersMerged // 0' "$REPORT")
FOLDERS_SKIPPED=$(jq -r '.metrics.foldersSkipped // 0' "$REPORT")

echo "Folders merged: $FOLDERS_MERGED"
echo "Folders skipped (up to date): $FOLDERS_SKIPPED"

# Check for warnings in output validation
WARNINGS=$(jq -r '.outputValidation.counts.warning // 0' "$REPORT")
if [[ "$WARNINGS" -gt 0 ]]; then
    echo "WARNING: $WARNINGS warning(s) in output validation"
    jq -r '.outputValidation.issues[] | select(.severity == "WARNING") | "  \(.code): \(.message)"' "$REPORT"
    exit 10
fi

echo "PASS: $FOLDERS_MERGED folder(s) merged, $FOLDERS_SKIPPED already up to date"
exit 0
