#!/bin/bash
#
# Pre-flight checks for the split-allconcepts-by-consent job, run on the Jenkins agent BEFORE any
# EC2 instance is provisioned.
#
# Environment:
#   TF_VAR_input_uri         required   the --input merged allConcepts CSV URI
#   TF_VAR_output_uri        required   the --output prefix
#   TF_VAR_depth             optional   default 1
#   TF_VAR_globals           optional   own (default) | by-patient
#   TF_VAR_fence_mapping     optional   URI, or "none"; blank means the job default
#   TF_VAR_force             optional   true allows a non-empty output prefix
#   TF_VAR_root_volume_size  optional   runner root disk in GiB (default 1000)
#   AWS_REGION               optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
# shellcheck disable=SC1091
source "$HERE/../common/lib.sh"

INPUT="$(trim "${TF_VAR_input_uri:?TF_VAR_input_uri is required}")"
OUTPUT="$(trim "${TF_VAR_output_uri:?TF_VAR_output_uri is required}")"
DEPTH="$(trim "${TF_VAR_depth:-1}")"
GLOBALS="$(trim "${TF_VAR_globals:-own}")"
FENCE="$(trim "${TF_VAR_fence_mapping:-}")"
FORCE="$(trim "${TF_VAR_force:-false}")"
DISK_GIB="$(trim "${TF_VAR_root_volume_size:-1000}")"
REGION="${AWS_REGION:-us-east-1}"

# The legacy bucket and its nightly in-boundary mirror prefix. In-boundary runners must never
# write there; the job refuses too, but failing here saves an instance launch.
MIRROR_MARKER="avillach-73-bdcatalyst-etl"

s3_bucket() { local rest="${1#s3://}"; echo "${rest%%/*}"; }
s3_key()    { local rest="${1#s3://}"; echo "${rest#*/}"; }

uri_exists() {
  local uri="$1"
  if [[ "$uri" == s3://* ]]; then
    aws s3api head-object --bucket "$(s3_bucket "$uri")" --key "$(s3_key "$uri")" --region "$REGION" >/dev/null 2>&1
  else
    [[ -f "$uri" ]]
  fi
}

# Prints the object count under a prefix (0 or 1: we only ask for one key).
prefix_object_count() {
  local uri="$1" key
  key="$(s3_key "$uri")"
  [[ "$key" == */ || -z "$key" ]] || key="$key/"
  # shellcheck disable=SC2016  # the backticks are a JMESPath literal, not command substitution
  aws s3api list-objects-v2 --bucket "$(s3_bucket "$uri")" --prefix "$key" --max-items 1 \
    --query 'length(Contents || `[]`)' --output text --region "$REGION" 2>/dev/null
}

output_prefix_is_empty() {
  local n
  n="$(prefix_object_count "$OUTPUT")" || return 1
  [[ "$n" == "0" ]]
}

input_size_bytes() {
  aws s3api head-object --bucket "$(s3_bucket "$INPUT")" --key "$(s3_key "$INPUT")" \
    --query ContentLength --output text --region "$REGION" 2>/dev/null
}

not_under_mirror() { [[ "$1" != *"$MIRROR_MARKER"* ]]; }
is_uint()          { [[ "$1" =~ ^[0-9]+$ ]]; }
is_s3_uri()        { [[ "$1" == s3://* ]]; }
is_globals_mode()  { [[ "$1" == own || "$1" == by-patient ]]; }

echo "Pre-flight: split-allconcepts-by-consent"
echo "  input:    $INPUT"
echo "  output:   $OUTPUT"
echo "  depth:    $DEPTH"
echo "  globals:  $GLOBALS"
echo "  fence:    ${FENCE:-<job default>}"
echo "  force:    $FORCE"
echo "  disk:     ${DISK_GIB} GiB"
echo ""

# --- parameters --------------------------------------------------------------
check "depth is an integer >= 0"           is_uint "$DEPTH"
check "globals is own or by-patient"       is_globals_mode "$GLOBALS"
check "root volume size is a number"       is_uint "$DISK_GIB"

# --- input -------------------------------------------------------------------
check "input is an S3 URI"                 is_s3_uri "$INPUT"
check "merged allConcepts input exists: $INPUT" uri_exists "$INPUT"

# --- output ------------------------------------------------------------------
check "output is an S3 URI"                is_s3_uri "$OUTPUT"
check "output is not under $MIRROR_MARKER (the legacy bucket mirror)" not_under_mirror "$OUTPUT"
if [[ "$FORCE" == "true" ]]; then
  note "force=true: an existing object under the output prefix will be overwritten (never deleted)"
elif [[ "$OUTPUT" == s3://* ]] && not_under_mirror "$OUTPUT"; then
  check "output prefix is empty (or pass force=true)" output_prefix_is_empty
fi

# --- fence mapping (report-only cross-check; never blocks) ----------------------
FENCE_LC="$(printf '%s' "$FENCE" | tr '[:upper:]' '[:lower:]')"
if [[ -n "$FENCE" && "$FENCE_LC" != "none" ]]; then
  soft "fence mapping is readable: $FENCE" uri_exists "$FENCE"
fi

# --- disk: the job spools every output byte locally before uploading ------------------
if [[ "$INPUT" == s3://* && "$DISK_GIB" =~ ^[0-9]+$ ]]; then
  SIZE="$(input_size_bytes)"
  if [[ "$SIZE" =~ ^[0-9]+$ ]]; then
    NEED_GIB=$(( (2 * SIZE + 1073741823) / 1073741824 + 30 ))   # 2x input + container image/OS headroom
    note "input is $(( SIZE / 1073741824 )) GiB; the spool needs about ${NEED_GIB} GiB"
    check "root volume (${DISK_GIB} GiB) covers 2x the input plus headroom (${NEED_GIB} GiB)" \
      test "$DISK_GIB" -ge "$NEED_GIB"
    if [[ "$GLOBALS" == "by-patient" ]]; then
      BP_GIB=$(( (4 * SIZE + 1073741823) / 1073741824 + 30 ))
      soft "by-patient copies globals into every consent partition; ${BP_GIB} GiB (4x) is safer than ${DISK_GIB} GiB" \
        test "$DISK_GIB" -ge "$BP_GIB"
    fi
  else
    warn "could not read the input size, so the disk size was not checked"
  fi
fi

summary
