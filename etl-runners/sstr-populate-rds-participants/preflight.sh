#!/bin/bash
#
# Pre-flight checks for the sstr-populate-rds-participants job, run on the Jenkins agent
# BEFORE any EC2 instance is provisioned.
#
# The job purges every existing consents row for --study-id before loading, in the same
# transaction, so a run that fails on a missing column rolls back safely but still costs an
# instance. Only the first 64 KiB of the input is read, so this is cheap regardless of file size.
#
# Environment:
#   TF_VAR_study_id   required   phs######
#   TF_VAR_input_uri  required   the SSTR TSV
#   TF_VAR_genomic    optional   true/false: the study's Data Type includes G (P/G); blank: unknown
#   AWS_REGION        optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

STUDY_ID="${TF_VAR_study_id:?TF_VAR_study_id is required}"
STUDY_ID="$(trim "$STUDY_ID")"
INPUT="${TF_VAR_input_uri:?TF_VAR_input_uri is required}"
INPUT="$(trim "$INPUT")"
GENOMIC="$(trim "${TF_VAR_genomic:-}")"
REGION="${AWS_REGION:-us-east-1}"

echo "Pre-flight: sstr-populate-rds-participants"
echo "  study-id: $STUDY_ID"
echo "  input:    $INPUT"
echo "  genomic:  ${GENOMIC:-unknown}"
echo ""

# Mirrors SstrPopulateRdsParticipantsJob.STUDY_ID_PATTERN.
check "study-id matches phs###### (6 digits)" grep -Eq '^phs[0-9]{6}$' <<<"$STUDY_ID"

HEAD_FILE=$(mktemp)
trap 'rm -f "$HEAD_FILE"' EXIT

if [[ "$INPUT" == s3://* ]]; then
  rest="${INPUT#s3://}"
  bucket="${rest%%/*}"
  key="${rest#*/}"

  if aws s3api head-object --bucket "$bucket" --key "$key" --region "$REGION" >/dev/null 2>&1; then
    check "input exists in S3" true
    SIZE=$(aws s3api head-object --bucket "$bucket" --key "$key" --region "$REGION" \
             --query ContentLength --output text)
    note "size: $SIZE bytes"
    check "input is not empty" test "$SIZE" -gt 0
    # Range GET: reads the header without pulling a multi-GB file to the agent.
    aws s3api get-object --bucket "$bucket" --key "$key" --region "$REGION" \
      --range "bytes=0-65535" "$HEAD_FILE" >/dev/null 2>&1 || true
  else
    fail "input does not exist in S3: $INPUT"
    summary
    exit 1
  fi
else
  if [[ -f "$INPUT" ]]; then
    check "input exists" true
    check "input is not empty" test -s "$INPUT"
    head -c 65536 "$INPUT" > "$HEAD_FILE"
  else
    fail "input file does not exist: $INPUT"
    summary
    exit 1
  fi
fi

HEADER=$(head -1 "$HEAD_FILE")
if [[ -z "$HEADER" ]]; then
  fail "input has no header line"
  summary
  exit 1
fi

# The job reads with DelimitedReader.TAB; a comma-delimited file parses as one column and fails
# with a confusing "missing required column(s)" listing the whole header.
TABS=$(awk -F'\t' '{print NF; exit}' "$HEAD_FILE")
check "header is tab-delimited (found $TABS column(s))" test "$TABS" -gt 1
if [[ "$TABS" -le 1 ]] && grep -q ',' <<<"$HEADER"; then
  note "the header contains commas -- this looks like a CSV, but the job requires TSV"
fi

# Exactly the columns requireColumns() asserts on, case-sensitively -- except the consent, which
# is CONSENT or consent_code in any case.
for col in dbgap_subject_id consent_abbreviation; do
  check "header has '$col'" \
    awk -F'\t' -v c="$col" 'NR==1 { for (i=1;i<=NF;i++) if ($i==c) found=1 } END { exit !found }' "$HEAD_FILE"
done
check "header has 'CONSENT' or 'consent_code'" \
  awk -F'\t' 'NR==1 { for (i=1;i<=NF;i++) { h=tolower($i); if (h=="consent" || h=="consent_code") found=1 } } END { exit !found }' "$HEAD_FILE"

# dbgap_sample_id is optional: without it no dbGaP sample rows load, which only matters for a
# genomic study (the job reports NO_SAMPLE_ID_COLUMN the same way).
if ! awk -F'\t' 'NR==1 { for (i=1;i<=NF;i++) if ($i=="dbgap_sample_id") found=1 } END { exit !found }' "$HEAD_FILE"; then
  if [[ "$GENOMIC" == "false" ]]; then
    note "no 'dbgap_sample_id' column: no dbGaP sample rows will load (fine: not a genomic study)"
  else
    warn "no 'dbgap_sample_id' column: no dbGaP sample rows will load for a $([[ "$GENOMIC" == "true" ]] && echo genomic || echo 'possibly genomic (Data Type unknown)') study"
  fi
fi

DATA_ROWS=$(tail -n +2 "$HEAD_FILE" | grep -c . || true)
check "input has data rows after the header" test "$DATA_ROWS" -gt 0

echo ""
note "header: $(tr '\t' '|' <<<"$HEADER")"

summary
