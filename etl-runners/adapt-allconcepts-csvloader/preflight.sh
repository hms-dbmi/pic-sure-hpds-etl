#!/bin/bash
#
# Pre-flight checks for the adapt-allconcepts-csvloader job, run on the Jenkins agent BEFORE any EC2
# instance is provisioned. Read-only AWS calls only.
#
# Environment:
#   TF_VAR_manifest_uri         required   s3:// URI of allConcepts_manifest.csv
#   TF_VAR_output_uri           required   s3:// prefix that must be empty
#   TF_VAR_studies              optional   comma-separated study ids (the size check counts only these)
#   TF_VAR_root_volume_size     optional   runner root volume in GiB (default 500, as in variables.tf)
#   AWS_REGION                  optional   default us-east-1
#
# Checks:
#   - the output is an s3:// prefix that does not name the legacy bucket / its in-boundary mirror
#     (avillach-73-bdcatalyst-etl anywhere in it) and holds no object yet
#   - the manifest exists, its bucket is versioned, and this role can ListBucketVersions and
#     GetObjectVersion there (the job reads every file by version id). One probe per run, not per file.
#   - the runner's root volume can hold the output: the job writes its output once locally before uploading, so
#     2 x (ContentLength of the in-scope manifest objects, global included) + 10 GiB of headroom (global rows
#     copied into several partitions, the container image) must fit TF_VAR_root_volume_size GiB
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
    # With --max-items and --output text the CLI prints the pagination token on a second line ("None" when there
    # is none), so only the first line is the version id (seen on geno 2026-10-29: "<id>\nNone").
    VERSION_ID=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "$KEY" --max-items 1 \
        --query 'Versions[0].VersionId' --output text --region "$REGION" 2>/dev/null | head -n 1 || echo "")
    VERSION_ID="$(trim "$VERSION_ID")"
    check "role can ListBucketVersions on '$BUCKET'" test -n "$VERSION_ID" -a "$VERSION_ID" != "None"
    PROBE_ERR="$(mktemp)"
    probe_get_version() {
        aws s3api get-object --bucket "$BUCKET" --key "$KEY" --version-id "$VERSION_ID" --range bytes=0-0 \
            --region "$REGION" /dev/null 2>"$PROBE_ERR"
    }
    check "role can GetObjectVersion on '$BUCKET'" probe_get_version
    # check() hides the command's output; show the AWS error so a FAIL names its cause.
    if [ -s "$PROBE_ERR" ]; then sed 's/^/         aws: /' "$PROBE_ERR" | head -n 3; fi
    rm -f "$PROBE_ERR"
fi

# --- the runner's disk ------------------------------------------------------

# Sums the sizes of the objects the job will read. The manifest is read here only for its s3_uri and version_id
# columns (simple CSV: quotes are stripped; a comma inside a quoted field is not supported).
check_disk() {
    local root_gib="${TF_VAR_root_volume_size:-500}" studies sum=0 n=0 part uri ver size b k study rows
    studies=",$(trim "${TF_VAR_studies:-}"),"
    studies="${studies// /}"
    if [[ ! "$root_gib" =~ ^[0-9]+$ ]]; then
        fail "ROOT_VOLUME_GB must be a whole number of GiB, got: $root_gib"
        return
    fi
    if ! rows=$(aws s3 cp "$MANIFEST" - --region "$REGION" 2>/dev/null | tail -n +2 | tr -d '\r'); then
        fail "cannot read the manifest to size the run: $MANIFEST"
        return
    fi
    while IFS=, read -r part uri ver _; do
        [[ -n "$part" ]] || continue
        part="${part//\"/}"
        uri="${uri//\"/}"
        ver="${ver//\"/}"
        if [[ "$studies" != ",," && "$part" != global ]]; then
            study="${part%.c*}"
            [[ "$studies" == *",$study,"* ]] || continue
        fi
        b="$(bucket_of "$uri")"
        k="$(key_of "$uri")"
        if ! size=$(aws s3api head-object --bucket "$b" --key "$k" --version-id "$ver" --query ContentLength --output text \
            --region "$REGION" 2>/dev/null) || [[ ! "$size" =~ ^[0-9]+$ ]]; then
            fail "cannot read the size of $uri (version $ver) for partition $part"
            return
        fi
        sum=$((sum + size))
        n=$((n + 1))
    done <<<"$rows"
    local need=$((2 * sum + 10 * 1024 * 1024 * 1024)) have=$((root_gib * 1024 * 1024 * 1024))
    note "in-scope objects: $n, $(awk -v b="$sum" 'BEGIN { printf "%.2f", b / 1073741824 }') GiB; need 2 x that + 10 GiB = $(awk -v b="$need" 'BEGIN { printf "%.2f", b / 1073741824 }') GiB; root volume: $root_gib GiB"
    check "root volume ($root_gib GiB) holds 2 x the input ($sum bytes) + 10 GiB" test "$need" -le "$have"
}

if [[ "$MANIFEST" == s3://*/* ]]; then
    check_disk
fi

summary
