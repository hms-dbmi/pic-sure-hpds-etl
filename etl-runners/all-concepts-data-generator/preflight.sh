#!/bin/bash
#
# Pre-flight checks for the all-concepts-data-generator job, run on the Jenkins
# agent BEFORE any EC2 instance is provisioned.
#
# Checks what is knowable from the inputs alone: the study id, that the mapping
# file and at least one decoded data CSV exist, and that the output is an s3://
# prefix on a VERSIONED bucket. The job overwrites each study's per-consent files
# in place (and removes ones for consent groups that no longer produce rows), so
# versioning is what keeps every previous version recoverable -- it is a hard
# requirement, not a nicety. merge-allconcepts' deletion check relies on it too.
#
# Environment:
#   TF_VAR_study_id      required   dbGaP study id
#   TF_VAR_data_dir      required   decoded data CSVs location
#   TF_VAR_mapping_uri   required   mapping CSV location
#   TF_VAR_output_uri    required   output prefix (s3:// only)
#   AWS_REGION           optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

STUDY_ID="$(trim "${TF_VAR_study_id:?TF_VAR_study_id is required}")"
DATA_DIR="$(trim "${TF_VAR_data_dir:?TF_VAR_data_dir is required}")"
MAPPING="$(trim "${TF_VAR_mapping_uri:?TF_VAR_mapping_uri is required}")"
OUTPUT="$(trim "${TF_VAR_output_uri:?TF_VAR_output_uri is required}")"
REGION="${AWS_REGION:-us-east-1}"

uri_exists() {
  local uri="$1"
  if [[ "$uri" == s3://* ]]; then
    local rest="${uri#s3://}"
    aws s3api head-object --bucket "${rest%%/*}" --key "${rest#*/}" --region "$REGION" >/dev/null 2>&1
  else
    [[ -f "$uri" ]]
  fi
}

# Number of .csv files directly under a directory/prefix.
csv_count() {
  local dir="${1%/}/"
  if [[ "$dir" == s3://* ]]; then
    aws s3 ls "$dir" --region "$REGION" 2>/dev/null | awk '$NF ~ /\.[cC][sS][vV]$/' | wc -l | tr -d ' '
  else
    find "$dir" -maxdepth 1 -type f -iname '*.csv' 2>/dev/null | wc -l | tr -d ' '
  fi
}

echo "Pre-flight: all-concepts-data-generator"
echo "  study-id: $STUDY_ID"
echo "  data-dir: $DATA_DIR"
echo "  mapping:  $MAPPING"
echo "  output:   $OUTPUT"
echo ""

# --- checks ---

check "study-id matches phs######: $STUDY_ID" bash -c '[[ "$1" =~ ^phs[0-9]{6}$ ]]' -- "$STUDY_ID"

check "mapping file exists: $MAPPING" uri_exists "$MAPPING"

CSVS=$(csv_count "$DATA_DIR")
check "data-dir holds decoded data CSVs ($CSVS found): $DATA_DIR" test "${CSVS:-0}" -gt 0

if [[ "$OUTPUT" != s3://* ]]; then
    # A local path resolves inside the runner container, destroyed with the instance:
    # a green run would silently discard every per-consent file.
    fail "output must be an s3:// URI, got '$OUTPUT'"
else
    BUCKET="${OUTPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    VERSIONING=$(aws s3api get-bucket-versioning --bucket "$BUCKET" --region "$REGION" \
                   --query Status --output text 2>/dev/null || echo unknown)
    check "output bucket '$BUCKET' has versioning Enabled (got: $VERSIONING) -- overwrites must stay recoverable" \
        test "$VERSIONING" = "Enabled"
fi

summary
