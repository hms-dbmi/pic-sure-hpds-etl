#!/bin/bash
#
# Pre-flight checks for the generate-allconcepts-manifest job, run on the Jenkins agent
# BEFORE any EC2 instance is provisioned.
#
# Environment:
#   TF_VAR_input_uri            required   data root (s3://) where allConcepts_manifest.csv is written
#   TF_VAR_global_allconcepts_uri required  global_AllConcepts.csv or its folder (s3://)
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

echo "Pre-flight: generate-allconcepts-manifest"
echo "  input: $INPUT"
echo ""

if [[ "$INPUT" != s3://* ]]; then
    fail "INPUT must be an s3:// URI (the manifest must be versioned), got: $INPUT"
else
    BUCKET="${INPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "S3 bucket '$BUCKET' is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
    # The manifest is overwritten in place; versioning is what keeps every earlier one.
    VERSIONING=$(aws s3api get-bucket-versioning --bucket "$BUCKET" --region "$REGION" --query Status --output text 2>/dev/null || true)
    check "S3 bucket '$BUCKET' has versioning Enabled (is: ${VERSIONING:-never enabled})" test "$VERSIONING" = "Enabled"
fi

GLOBAL="$(trim "${TF_VAR_global_allconcepts_uri:-}")"
[[ "$GLOBAL" == */ ]] && GLOBAL="${GLOBAL}global_AllConcepts.csv"
if [[ "$GLOBAL" != s3://* ]]; then
    fail "GLOBAL_ALLCONCEPTS must be an s3:// URI (its version is pinned in the manifest), got: '$GLOBAL'"
else
    GBUCKET="${GLOBAL#s3://}"
    GBUCKET="${GBUCKET%%/*}"
    GVERSIONING=$(aws s3api get-bucket-versioning --bucket "$GBUCKET" --region "$REGION" --query Status --output text 2>/dev/null || true)
    check "S3 bucket '$GBUCKET' (global AllConcepts) has versioning Enabled (is: ${GVERSIONING:-never enabled})" test "$GVERSIONING" = "Enabled"
    # Not a failure here: in a pipeline, generate-global-all-concepts may write it later in the same run.
    if aws s3 ls "$GLOBAL" --region "$REGION" >/dev/null 2>&1; then
        check "global AllConcepts exists: $GLOBAL" true
    else
        warn "global AllConcepts not found yet: $GLOBAL (the job fails if it is still missing when it runs)"
    fi
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
