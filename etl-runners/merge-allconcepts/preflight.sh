#!/bin/bash
#
# Pre-flight checks for the merge-allconcepts job, run on the Jenkins agent
# BEFORE any EC2 instance is provisioned.
#
# Environment:
#   TF_VAR_input_uri   required   S3 prefix containing study/consent folders
#   AWS_REGION         optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

INPUT="${TF_VAR_input_uri:?TF_VAR_input_uri is required}"
INPUT="$(trim "$INPUT")"
REGION="${AWS_REGION:-us-east-1}"

echo "Pre-flight: merge-allconcepts"
echo "  input: $INPUT"
echo ""

# --- checks ---

if [[ -z "$INPUT" ]]; then
    fail "INPUT is required: the s3:// prefix containing study/consent allConcepts folders."
fi

if [[ "$INPUT" != s3://* ]]; then
    fail "INPUT must be an s3:// URI (versioning requires S3), got: $INPUT"
else
    BUCKET="${INPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "S3 bucket '$BUCKET' is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
fi

summary
