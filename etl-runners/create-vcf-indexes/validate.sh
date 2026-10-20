#!/bin/bash
# Post-run validation for create-vcf-indexes.
# Checks the JSON report for expected fields and non-zero counts.
#
# Exit codes:
#   0  = pass
#   10 = warnings
#   1  = hard failure
set -euo pipefail

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"

REPORT="$REPORTS_DIR/create-vcf-indexes-${RUN_ID}.json"

if [[ ! -f "$REPORT" ]]; then
    echo "FAIL: no report at $REPORT. The job writes it as it finishes, so the job stopped early (timeout, crash or lost instance); its exit code and runner log say why."
    exit 1
fi

echo "--- Report: $REPORT ---"
jq . "$REPORT"

# Consent groups with bad data (a separator in a sample id, a comma or line break in the
# study abbreviation) are held back: not written, and their previously published files are
# deleted (so stale data cannot sit beside the new groups), while every good group is written.
# Each one is an output-validation error, so the job fails; list them, and what was deleted, so
# the failure names exactly what still needs fixing.
HELD_BACK=$(jq -r '(.metrics.heldBackGroups // []) | length' "$REPORT")
if [[ "$HELD_BACK" -gt 0 ]]; then
    echo "HELD BACK: $HELD_BACK consent group(s) not written because of bad data; $(jq -r '.metrics.heldBackFilesDeleted // 0' "$REPORT") previously published file(s) deleted:"
    jq -r '.metrics.heldBackGroups[] | "  \(.consentGroup) (study \(.studyId), consent \(.consent)): \(.reasons | join(", "))\(if .badSampleIdCount > 0 then " -- \(.badSampleIdCount) bad sample id(s), e.g. \(.badSampleIds | map(@json) | join(" "))" else "" end)\n    deleted: \(if ((.deletedFiles // []) | length) > 0 then .deletedFiles | join(" ") else "none (nothing was published before)" end)"' "$REPORT"
fi

STATUS=$(jq -r '.status' "$REPORT")
if [[ "$STATUS" != "SUCCESS" && "$STATUS" != "SUCCESS_WITH_WARNINGS" ]]; then
    echo "FAIL: job status is $STATUS"
    exit 1
fi

STUDIES_PROCESSED=$(jq -r '.metrics.studiesProcessed // 0' "$REPORT")
if [[ "$STUDIES_PROCESSED" -le 0 ]]; then
    # An empty workload is legitimate when the job itself flagged it: the managed
    # inputs simply held no matching genomic studies. The job exits
    # SUCCESS_WITH_WARNINGS for that case; mirror it as a warning, not a failure.
    if jq -e '(.inputValidation.issues // [])[] | select(.code == "NO_GENOMIC_STUDIES")' "$REPORT" >/dev/null 2>&1; then
        echo "WARNING: no genomic studies in the managed inputs — nothing to index"
        exit 10
    fi
    echo "FAIL: studiesProcessed is $STUDIES_PROCESSED — expected at least 1"
    exit 1
fi

CONSENT_GROUPS=$(jq -r '.metrics.consentGroupsProcessed // 0' "$REPORT")
if [[ "$CONSENT_GROUPS" -le 0 ]]; then
    echo "FAIL: consentGroupsProcessed is $CONSENT_GROUPS — expected at least 1"
    exit 1
fi

TOTAL_SAMPLES=$(jq -r '.metrics.totalSamples // 0' "$REPORT")
if [[ "$TOTAL_SAMPLES" -le 0 ]]; then
    echo "FAIL: totalSamples is $TOTAL_SAMPLES — expected at least 1"
    exit 1
fi

OUTPUT_FILES=$(jq -r '.metrics.outputFiles // empty' "$REPORT")
if [[ -z "$OUTPUT_FILES" || "$OUTPUT_FILES" == "null" || "$OUTPUT_FILES" == "[]" ]]; then
    echo "FAIL: outputFiles is empty — expected at least one output file"
    exit 1
fi

echo "Studies processed: $STUDIES_PROCESSED"
echo "Consent groups processed: $CONSENT_GROUPS"
echo "Total samples: $TOTAL_SAMPLES"

# Check for warnings in output validation
WARNINGS=$(jq -r '.outputValidation.counts.warning // 0' "$REPORT")
if [[ "$WARNINGS" -gt 0 ]]; then
    echo "WARNING: $WARNINGS warning(s) in output validation"
    jq -r '.outputValidation.issues[] | select(.severity == "WARNING") | "  \(.code): \(.message)"' "$REPORT"
    exit 10
fi

echo "PASS: $TOTAL_SAMPLES samples across $STUDIES_PROCESSED studies and $CONSENT_GROUPS consent groups"
exit 0
