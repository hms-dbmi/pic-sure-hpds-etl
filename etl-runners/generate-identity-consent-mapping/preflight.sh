#!/bin/bash
#
# Pre-flight checks for the generate-identity-consent-mapping job, run on the
# Jenkins agent BEFORE any EC2 instance is provisioned.
#
# Validates the output bucket (default credentials / dbgap-etl profile) and the
# NHLBI exchange bucket (under the 'nhlbi-exchange' profile the Jenkinsfile's
# Init stage writes, which assumes the role passed as ROLE_ARN).
#
# Environment:
#   TF_VAR_output_uri      required   where the mapping CSV(s) are written
#   TF_VAR_base_uri        required   bucket/prefix holding the DMC drops
#   TF_VAR_input_role_arn  required   role assumed for all reads of the base
#   TF_VAR_dataset_prefix  optional   pin a drop; blank = latest by date
#   AWS_REGION             optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

OUTPUT="$(trim "${TF_VAR_output_uri:?TF_VAR_output_uri is required}")"
BASE="$(trim "${TF_VAR_base_uri:?TF_VAR_base_uri is required}")"
ROLE_ARN="$(trim "${TF_VAR_input_role_arn:-}")"
DATASET="$(trim "${TF_VAR_dataset_prefix:-}")"
REGION="${AWS_REGION:-us-east-1}"

echo "Pre-flight: generate-identity-consent-mapping"
echo "  base:    $BASE"
echo "  dataset: ${DATASET:-<latest by date>}"
echo "  output:  $OUTPUT"
echo ""

# --- checks ---

if [[ -z "$OUTPUT" ]]; then
    fail "OUTPUT is required: the s3:// URI or local path for the mapping CSV(s)."
fi

if [[ "$BASE" == s3://* && ! "$ROLE_ARN" =~ ^arn:aws:iam::[0-9]{12}:role/ ]]; then
    fail "ROLE_ARN must be an IAM role ARN when the base is on S3, got: '${ROLE_ARN:-<empty>}'"
fi

if [[ "$OUTPUT" == s3://* ]]; then
    BUCKET="${OUTPUT#s3://}"
    BUCKET="${BUCKET%%/*}"
    check "Output bucket '$BUCKET' is reachable (else: check credentials and region)" \
        aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
fi

if [[ "$BASE" == s3://* ]]; then
    # The exchange bucket is readable only under the NHLBI role; the Init stage
    # wrote an 'nhlbi-exchange' profile for it into $AWS_CONFIG_FILE.
    check "Exchange bucket '$BASE' lists under the NHLBI role (else: role trust/instance profile)" \
        env AWS_PROFILE=nhlbi-exchange aws s3 ls "$BASE/" --region "$REGION"

    if [[ -n "$DATASET" ]]; then
        check "Pinned dataset '$DATASET' exists under the base" \
            env AWS_PROFILE=nhlbi-exchange aws s3 ls "$BASE/$DATASET/" --region "$REGION"
    else
        LATEST=$(env AWS_PROFILE=nhlbi-exchange aws s3 ls "$BASE/" --region "$REGION" 2>/dev/null \
            | sed -n 's/.*PRE \(BDC-DMC-Harmonization-Examples-[0-9]\{8\}\)\/.*/\1/p' \
            | sort | tail -1)
        if [[ -n "$LATEST" ]]; then
            check "Latest drop resolves deterministically: $LATEST" true
        else
            soft "No BDC-DMC-Harmonization-Examples-YYYYMMDD prefix visible under $BASE"
        fi
    fi
fi

summary
