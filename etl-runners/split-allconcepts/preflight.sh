#!/bin/bash
#
# Pre-flight checks for the split-allconcepts job, run on the Jenkins agent BEFORE any
# EC2 instance is provisioned.
#
# Environment:
#   TF_VAR_study_id      required   the --study-id
#   TF_VAR_abbreviation  required   the --abbreviation
#   TF_VAR_input_uri     required   the --input allConcepts CSV URI
#   TF_VAR_mapping_uri   required   the --mapping hpds_id_mapping CSV URI
#   TF_VAR_output_uri    required   the --output directory
#   AWS_REGION           optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

STUDY_ID="${TF_VAR_study_id:?TF_VAR_study_id is required}"
ABV="${TF_VAR_abbreviation:?TF_VAR_abbreviation is required}"
INPUT="${TF_VAR_input_uri:?TF_VAR_input_uri is required}"
MAPPING="${TF_VAR_mapping_uri:?TF_VAR_mapping_uri is required}"
OUTPUT="${TF_VAR_output_uri:?TF_VAR_output_uri is required}"
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

echo "Pre-flight: split-allconcepts"
echo "  study-id:     $STUDY_ID"
echo "  abbreviation: $ABV"
echo "  input:        $INPUT"
echo "  mapping:      $MAPPING"
echo "  output:       $OUTPUT"
echo ""

# --- study-id format ---------------------------------------------------------
check "study-id matches phs######" bash -c '[[ "$1" =~ ^phs[0-9]{6}$ ]]' -- "$STUDY_ID"

# --- abbreviation non-empty --------------------------------------------------
check "abbreviation is non-empty" test -n "$ABV"

# --- input allConcepts exists ------------------------------------------------
check "allConcepts input exists" uri_exists "$INPUT"

# --- mapping CSV exists ------------------------------------------------------
# The mapping CSV is uploaded by the orchestrator (MAPPING_UPLOAD_BASE) after
# participants-migration completes; it does not exist yet when running
# preflight-only, so this stays a soft check. This runs on the Jenkins agent
# under the dbgap-etl profile, which can head-object the 73 bucket.
soft "mapping CSV exists (uploaded by the orchestrator after participants-migration)" uri_exists "$MAPPING"

# --- output looks reasonable -------------------------------------------------
if [[ "$OUTPUT" == s3://* ]]; then
  check "output is an S3 URI" true
elif [[ -d "$OUTPUT" ]] || [[ "$OUTPUT" == ./* ]] || [[ "$OUTPUT" == /* ]]; then
  check "output is a local path" true
else
  soft "output path '$OUTPUT' does not exist yet (will be created at runtime)" true
fi

summary
