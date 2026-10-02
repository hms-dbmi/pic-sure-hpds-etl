#!/bin/bash
# Post-run validation for all-concepts-data-generator.
#
# The exit code and the output are independent gates: besides the report's own status and
# row counts, every per-consent file the report lists must really be in S3, and there must be
# exactly one per consent group with rows.
#
# Usage: validate.sh <reports-dir> <run-id>
# Exit:  0 pass | 10 pass with warnings (e.g. unmapped patients, stale files removed) | 1 failed
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REPORTS_DIR="${1:?Usage: validate.sh <reports-dir> <run-id>}"
RUN_ID="${2:?Usage: validate.sh <reports-dir> <run-id>}"
REGION="${AWS_REGION:-us-east-1}"

REPORT="$REPORTS_DIR/all-concepts-data-generator-${RUN_ID}.json"

if [[ ! -f "$REPORT" ]]; then
    echo "FAIL: report not found: $REPORT"
    exit 1
fi

echo "--- Report: $REPORT ---"
jq . "$REPORT"
echo ""

s3_exists() {
  local rest="${1#s3://}"
  aws s3api head-object --bucket "${rest%%/*}" --key "${rest#*/}" --region "$REGION" >/dev/null 2>&1
}

STATUS=$(jq -r '.status' "$REPORT")
ROWS_PROCESSED=$(jq -r '.metrics.rowsProcessed // 0' "$REPORT")
CONSENTS_WITH_ROWS=$(jq -r '.metrics.rowsPerConsent // {} | length' "$REPORT")
OUTPUT_FILES=$(jq -r '.metrics.outputFiles // [] | length' "$REPORT")

echo "Study:          $(jq -r '.metrics.studyId' "$REPORT")"
echo "Consent groups: $(jq -r '.metrics.consentGroups // 0' "$REPORT") ($CONSENTS_WITH_ROWS with rows)"
echo "Rows processed: $ROWS_PROCESSED"
echo "--- Per-consent rows ---"
jq -r '.metrics.rowsPerConsent // {} | to_entries[] | "  \(.key): \(.value) row(s)"' "$REPORT"
echo ""

check "status is SUCCESS or SUCCESS_WITH_WARNINGS (got $STATUS)" \
    bash -c '[[ "$1" == SUCCESS || "$1" == SUCCESS_WITH_WARNINGS ]]' -- "$STATUS"
check "rowsProcessed > 0 (got $ROWS_PROCESSED)" test "$ROWS_PROCESSED" -gt 0
check "one output file per consent group with rows ($OUTPUT_FILES files, $CONSENTS_WITH_ROWS groups)" \
    test "$OUTPUT_FILES" -eq "$CONSENTS_WITH_ROWS"

while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    if [[ "$f" == s3://* ]]; then
        check "present in S3: $f" s3_exists "$f"
    else
        fail "output is not on S3 (lost with the runner container): $f"
    fi
done < <(jq -r '.metrics.outputFiles // [] | .[]' "$REPORT")

while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    warn "stale file removed (its consent group produced no rows): $f"
done < <(jq -r '.metrics.staleFilesRemoved // [] | .[]' "$REPORT")

# Other output-validation warnings (e.g. UNMAPPED_PATIENTS); stale removals were reported above.
while IFS= read -r line; do
    [[ -n "$line" ]] && warn "$line"
done < <(jq -r '.outputValidation.issues // [] | .[]
                | select(.severity == "WARNING" and .code != "STALE_OUTPUT_REMOVED")
                | "\(.code): \(.message)"' "$REPORT")

summary
