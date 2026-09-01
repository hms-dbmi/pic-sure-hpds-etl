#!/bin/bash
#
# Pre-flight checks for the all-concepts-data-generator job, run on the Jenkins
# agent BEFORE any EC2 instance is provisioned.
#
# Validates that required params are set and S3 locations are reachable.
#
# Environment:
#   TF_VAR_study_id      required   dbGaP study id
#   TF_VAR_data_dir      required   decoded data CSVs location
#   TF_VAR_mapping_uri   required   mapping CSV location
#   TF_VAR_output_uri    required   output directory
#   AWS_REGION           optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

STUDY_ID="${TF_VAR_study_id:?TF_VAR_study_id is required}"
STUDY_ID="$(trim "$STUDY_ID")"
DATA_DIR="${TF_VAR_data_dir:?TF_VAR_data_dir is required}"
DATA_DIR="$(trim "$DATA_DIR")"
MAPPING="${TF_VAR_mapping_uri:?TF_VAR_mapping_uri is required}"
MAPPING="$(trim "$MAPPING")"
OUTPUT="${TF_VAR_output_uri:?TF_VAR_output_uri is required}"
OUTPUT="$(trim "$OUTPUT")"
REGION="${AWS_REGION:-us-east-1}"

echo "Pre-flight: all-concepts-data-generator"
echo "  study-id: $STUDY_ID"
echo "  data-dir: $DATA_DIR"
echo "  mapping:  $MAPPING"
echo "  output:   $OUTPUT"
echo ""

# --- checks ---

# Validate study id format
check "study-id matches phs######: $STUDY_ID" bash -c '[[ "$1" =~ ^phs[0-9]{6}$ ]]' -- "$STUDY_ID"
# Check data-dir reachability
if [[ "$DATA_DIR" == s3://* ]]; then
    BUCKET="${DATA_DIR#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "S3 bucket for data-dir is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
else
    if [[ ! -d "$DATA_DIR" ]]; then
        warn "Local data directory '$DATA_DIR' does not exist yet"
    else
        check "Local data directory exists" true
    fi
fi

# Check mapping reachability
if [[ "$MAPPING" == s3://* ]]; then
    BUCKET="${MAPPING#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "S3 bucket for mapping is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
fi

# Check output reachability
if [[ "$OUTPUT" == s3://* ]]; then
    BUCKET="${OUTPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "S3 bucket for output is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
else
    OUT_DIR="$OUTPUT"
    if [[ ! "$OUTPUT" == */ ]]; then
        OUT_DIR="$(dirname "$OUTPUT")"
    fi
    if [[ ! -d "$OUT_DIR" ]]; then
        warn "Local output directory '$OUT_DIR' does not exist (the job will create it)"
    else
        check "Local output directory exists" true
    fi
fi

summary
