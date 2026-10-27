#!/bin/bash
#
# Pre-flight checks for the adapt-allconcepts-csvloader job, run on the Jenkins agent BEFORE any EC2
# instance is provisioned. Read-only AWS calls only.
#
# Environment:
#   TF_VAR_manifest_uri         required   s3:// URI of allConcepts_manifest.csv
#   TF_VAR_output_uri           required   s3:// prefix that must be empty
#   AWS_REGION                  optional   default us-east-1
#
# Checks:
#   - the output is an s3:// prefix that does not name the legacy bucket / its in-boundary mirror
#     (avillach-73-bdcatalyst-etl anywhere in it) and holds no object yet
#   - the manifest exists, its bucket is versioned, and this role can ListBucketVersions and
#     GetObjectVersion there (the job reads every file by version id). One probe per run, not per file.
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

MANIFEST="${TF_VAR_manifest_uri:?TF_VAR_manifest_uri is required}"
MANIFEST="$(trim "$MANIFEST")"
OUTPUT="${TF_VAR_output_uri:?TF_VAR_output_uri is required}"
OUTPUT="$(trim "$OUTPUT")"
REGION="${AWS_REGION:-us-east-1}"
LEGACY_MARKER="avillach-73-bdcatalyst-etl"

echo "Pre-flight: adapt-allconcepts-csvloader"
echo "  manifest: $MANIFEST"
echo "  output:   $OUTPUT"
echo ""

bucket_of() { local rest="${1#s3://}"; echo "${rest%%/*}"; }
key_of() { local rest="${1#s3://}"; [[ "$rest" == */* ]] && echo "${rest#*/}" || echo ""; }

# --- the output -------------------------------------------------------------

if [[ "$OUTPUT" != s3://*/* ]]; then
    fail "OUTPUT must be an s3://bucket/prefix URI (the runner writes to S3), got: $OUTPUT"
elif [[ "$OUTPUT" == *"$LEGACY_MARKER"* ]]; then
    fail "OUTPUT '$OUTPUT' is under $LEGACY_MARKER, the legacy bucket and its in-boundary mirror, which no run may write"
else
    OUT_BUCKET="$(bucket_of "$OUTPUT")"
    OUT_PREFIX="$(key_of "$OUTPUT")"
    [[ "$OUT_PREFIX" == */ ]] || OUT_PREFIX="$OUT_PREFIX/"
    check "output bucket '$OUT_BUCKET' is reachable" aws s3api head-bucket --bucket "$OUT_BUCKET" --region "$REGION"
    COUNT=$(aws s3api list-objects-v2 --bucket "$OUT_BUCKET" --prefix "$OUT_PREFIX" --max-keys 1 \
        --query KeyCount --output text --region "$REGION" 2>/dev/null || echo unknown)
    check "output prefix s3://$OUT_BUCKET/$OUT_PREFIX holds no object (KeyCount: $COUNT)" test "$COUNT" = "0"
fi

# --- the manifest and the version-level permissions -------------------------

if [[ "$MANIFEST" != s3://*/* ]]; then
    fail "MANIFEST must be an s3:// URI (versioned reads need S3), got: $MANIFEST"
else
    BUCKET="$(bucket_of "$MANIFEST")"
    KEY="$(key_of "$MANIFEST")"
    check "manifest bucket '$BUCKET' is reachable (else: check credentials and region)" aws s3api head-bucket --bucket "$BUCKET" --region "$REGION"
    VERSIONING=$(aws s3api get-bucket-versioning --bucket "$BUCKET" --region "$REGION" --query Status --output text 2>/dev/null || true)
    check "manifest bucket '$BUCKET' has versioning Enabled (is: ${VERSIONING:-never enabled})" test "$VERSIONING" = "Enabled"
    check "manifest exists: $MANIFEST" aws s3api head-object --bucket "$BUCKET" --key "$KEY" --region "$REGION"

    # One-time probe of the two version-level permissions the job needs on the manifest's bucket.
    VERSION_ID=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "$KEY" --max-items 1 \
        --query 'Versions[0].VersionId' --output text --region "$REGION" 2>/dev/null || echo "")
    check "role can ListBucketVersions on '$BUCKET'" test -n "$VERSION_ID" -a "$VERSION_ID" != "None"
    probe_get_version() {
        aws s3api get-object --bucket "$BUCKET" --key "$KEY" --version-id "$VERSION_ID" --range bytes=0-0 \
            --region "$REGION" /dev/null
    }
    check "role can GetObjectVersion on '$BUCKET'" probe_get_version
fi

summary
