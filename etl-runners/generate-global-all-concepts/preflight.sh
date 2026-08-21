#!/bin/bash
#
# Pre-flight checks for the generate-global-all-concepts job, run on the Jenkins agent
# BEFORE any EC2 instance is provisioned.
#
# Validates that --output is set and the managed inputs source is reachable.
#
# Environment:
#   TF_VAR_output_uri  required   where global_AllConcepts.csv is written
#   AWS_REGION         optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

OUTPUT="${TF_VAR_output_uri:?TF_VAR_output_uri is required}"
REGION="${AWS_REGION:-us-east-1}"

echo "Pre-flight: generate-global-all-concepts"
echo "  output: $OUTPUT"
echo ""

# --- checks ---

if [[ -z "$OUTPUT" ]]; then
    fail "OUTPUT is required: the s3:// URI or local path for global_AllConcepts.csv."
fi

if [[ "$OUTPUT" == s3://* ]]; then
    BUCKET="${OUTPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    if ! aws s3api head-bucket --bucket "$BUCKET" --region "$REGION" 2>/dev/null; then
        fail "Cannot reach S3 bucket '$BUCKET' — check credentials and region."
    fi
    check "S3 bucket '$BUCKET' is reachable"
else
    OUT_DIR="$OUTPUT"
    if [[ ! "$OUTPUT" == */ ]]; then
        OUT_DIR="$(dirname "$OUTPUT")"
    fi
    if [[ ! -d "$OUT_DIR" ]]; then
        warn "Local output directory '$OUT_DIR' does not exist (the job will create it)"
    else
        check "Local output directory '$OUT_DIR' exists"
    fi
fi

summary
