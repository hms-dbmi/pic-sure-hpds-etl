#!/bin/bash
#
# Pre-flight checks for the merge-allconcepts job, run on the Jenkins agent
# BEFORE any EC2 instance is provisioned.
#
# Environment:
#   TF_VAR_input_uri            required   S3 prefix containing study/consent folders
#   TF_VAR_managed_inputs_uri   optional   managed inputs CSV (blank: the job's configured default)
#   AWS_REGION                  optional   default us-east-1
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
    # The job detects changed sources from S3 version ids and refuses an unversioned bucket.
    VERSIONING=$(aws s3api get-bucket-versioning --bucket "$BUCKET" --region "$REGION" --query Status --output text 2>/dev/null || true)
    check "S3 bucket '$BUCKET' has versioning Enabled (is: ${VERSIONING:-never enabled})" test "$VERSIONING" = "Enabled"
fi

MANAGED_INPUTS="$(trim "${TF_VAR_managed_inputs_uri:-}")"
if [[ "$MANAGED_INPUTS" == s3://* ]]; then
    check "managed inputs exist: $MANAGED_INPUTS" aws s3 ls "$MANAGED_INPUTS" --region "$REGION"
elif [[ -n "$MANAGED_INPUTS" ]]; then
    check "managed inputs exist: $MANAGED_INPUTS" test -f "$MANAGED_INPUTS"
else
    warn "MANAGED_INPUTS is blank: the job falls back to its configured etl.managed-inputs.uri"
fi

summary
