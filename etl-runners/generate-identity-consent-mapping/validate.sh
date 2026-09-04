#!/bin/bash
# Post-run validation for generate-identity-consent-mapping.
# Checks the JSON report for expected fields and non-zero counts, and that the
# mapping CSV(s) actually landed at the output location.
#
# Environment (set by the Jenkinsfile):
#   TF_VAR_output_uri  where the CSV(s) were written (s3:// URI or local path)
#   TF_VAR_per_study   'true' when one CSV per study was requested
#
# Exit codes:
#   0  = pass
#   10 = warnings (e.g. identities spanning consent groups of one study)
#   1  = hard failure
set -euo pipefail

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"

REPORT="$REPORTS_DIR/generate-identity-consent-mapping-${RUN_ID}.json"

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

CONSENT_GROUPS=$(jq -r '.metrics.consentGroups // 0' "$REPORT")
if [[ "$CONSENT_GROUPS" -le 0 ]]; then
    echo "FAIL: consentGroups is $CONSENT_GROUPS — expected at least 1"
    exit 1
fi

ROWS=$(jq -r '.metrics.rows // 0' "$REPORT")
if [[ "$ROWS" -le 0 ]]; then
    echo "FAIL: rows is $ROWS — expected at least 1 mapping row"
    exit 1
fi

# The mapping must actually exist where the job says it wrote it.
OUTPUT="${TF_VAR_output_uri:-}"
if [[ "$OUTPUT" == s3://* ]]; then
    CSV_COUNT=$(aws s3 ls "${OUTPUT%/}/" --recursive 2>/dev/null \
        | grep -c 'identity_consent_mapping.*\.csv$' || true)
    if [[ "$CSV_COUNT" -le 0 ]]; then
        echo "FAIL: no identity_consent_mapping*.csv found under $OUTPUT"
        exit 1
    fi
    if [[ "${TF_VAR_per_study:-false}" != "true" && "$CSV_COUNT" -ne 1 ]]; then
        echo "FAIL: expected exactly 1 combined CSV under $OUTPUT, found $CSV_COUNT"
        exit 1
    fi
    echo "Output CSVs at $OUTPUT: $CSV_COUNT"
fi

BLANKS=$(jq -r '.metrics.blankIdentityRows // 0' "$REPORT")
DUPES=$(jq -r '.metrics.inGroupDuplicates // 0' "$REPORT")
echo "Consent groups: $CONSENT_GROUPS"
echo "Mapping rows: $ROWS"
echo "Blank identities: $BLANKS; in-group duplicates: $DUPES"

# Cross-consent identities are the job's own soft signal; mirror it as a warning.
CROSS=$(jq -r '.metrics.crossConsentIdentities // 0' "$REPORT")
WARNINGS=$(jq -r '.outputValidation.counts.warning // 0' "$REPORT")
if [[ "$CROSS" -gt 0 || "$WARNINGS" -gt 0 ]]; then
    echo "WARNING: $CROSS identity(ies) span consent groups of one study; $WARNINGS report warning(s)"
    jq -r '(.outputValidation.issues // [])[] | select(.severity == "WARNING") | "  \(.code): \(.message)"' "$REPORT"
    exit 10
fi

echo "PASS: $ROWS mapping rows across $CONSENT_GROUPS consent groups"
exit 0
