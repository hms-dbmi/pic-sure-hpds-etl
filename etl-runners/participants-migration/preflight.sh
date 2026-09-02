#!/bin/bash
#
# Pre-flight checks for the participants-migration job, run on the Jenkins agent BEFORE any
# EC2 instance is provisioned.
#
# ParticipantsMigrationJob discovers its per-study files by convention and treats a missing or
# malformed file as a per-study failure at run time. Those failures are knowable from the input
# layout alone, so checking here costs seconds instead of a partial migration.
#
# Environment:
#   TF_VAR_managed_inputs_uri   required   the --managed-inputs CSV
#   TF_VAR_data_folder_uri      required   the --data-folder folder
#   AWS_REGION                  optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

MANAGED_INPUTS="${TF_VAR_managed_inputs_uri:?TF_VAR_managed_inputs_uri is required}"
MANAGED_INPUTS="$(trim "$MANAGED_INPUTS")"
DATA_FOLDER="${TF_VAR_data_folder_uri:?TF_VAR_data_folder_uri is required}"
DATA_FOLDER="$(trim "$DATA_FOLDER")"
REGION="${AWS_REGION:-us-east-1}"

DATA_FOLDER="${DATA_FOLDER%/}"

uri_exists() {
  local uri="$1"
  if [[ "$uri" == s3://* ]]; then
    local rest="${uri#s3://}"
    aws s3api head-object --bucket "${rest%%/*}" --key "${rest#*/}" --region "$REGION" >/dev/null 2>&1
  else
    [[ -f "$uri" ]]
  fi
}

# Lists file names under an S3 prefix or local directory.
uri_ls() {
  local uri="$1"
  if [[ "$uri" == s3://* ]]; then
    aws s3 ls "${uri%/}/" --region "$REGION" 2>/dev/null | awk '{print $NF}'
  else
    ls -1 "$uri" 2>/dev/null
  fi
}

read_uri() {
  local uri="$1"
  if [[ "$uri" == s3://* ]]; then
    aws s3 cp "$uri" - --region "$REGION" 2>/dev/null
  else
    cat "$uri" 2>/dev/null
  fi
}

echo "Pre-flight: participants-migration"
echo "  managed-inputs: $MANAGED_INPUTS"
echo "  data-folder:    $DATA_FOLDER"
echo ""

# --- managed-inputs ------------------------------------------------------
check "managed-inputs exists: $MANAGED_INPUTS" uri_exists "$MANAGED_INPUTS"
if ! uri_exists "$MANAGED_INPUTS"; then
  summary
  exit 1
fi

MANIFEST=$(mktemp)
trap 'rm -f "$MANIFEST"' EXIT
read_uri "$MANAGED_INPUTS" > "$MANIFEST"

# Parsed with python3's csv module rather than awk: the study list is a spreadsheet export, so
# quoted fields containing commas are normal.
STUDIES=$(python3 - "$MANIFEST" <<'PY'
import csv, sys

ABV       = "Study Abbreviated Name"
SID       = "Study Identifier"
READY     = "Data is ready to process"
DATA_TYPE = "Data Type"
PROCESSED = "Data Processed"

REQUIRED = [ABV, SID, READY, DATA_TYPE, PROCESSED]

with open(sys.argv[1], newline="", encoding="utf-8-sig") as fh:
    reader = csv.DictReader(fh)
    missing = [c for c in REQUIRED if c not in (reader.fieldnames or [])]
    if missing:
        print("MISSING_COLUMNS\t" + ", ".join(missing))
        sys.exit(0)
    for row in reader:
        abv = (row.get(ABV) or "").strip()
        sid = (row.get(SID) or "").strip()
        if not abv or not sid:
            continue
        # Mirrors ParticipantsMigrationJob.parseReady exactly.
        ready = (row.get(READY) or "").strip().lower() in ("true", "yes", "1")
        print(f"{abv}\t{sid}\t{'ready' if ready else 'skip'}")
PY
) || { fail "could not parse managed-inputs as CSV"; summary; exit 1; }

if [[ "$STUDIES" == MISSING_COLUMNS* ]]; then
  fail "managed-inputs is missing required column(s): ${STUDIES#MISSING_COLUMNS$'\t'}"
  note "expected: 'Study Abbreviated Name', 'Study Identifier', 'Data is ready to process', 'Data Type', 'Data Processed'"
  summary
  exit 1
fi

check "managed-inputs has the required columns" true

READY_COUNT=$(awk -F'\t' '$3=="ready"' <<<"$STUDIES" | grep -c . || true)
check "at least one study is marked ready" test "$READY_COUNT" -gt 0
note "$READY_COUNT of $(grep -c . <<<"$STUDIES") studies marked ready to process"

# --- shared GLOBAL_allConcepts_merged.csv --------------------------------
# Required unconditionally: execute() reads it before looking at any study, so a missing
# one aborts the entire run rather than just the studies that would have used it.
ALL_CONCEPTS="$DATA_FOLDER/general/completed/GLOBAL_allConcepts_merged.csv"
check "shared GLOBAL_allConcepts_merged.csv exists (read before any study is processed): $ALL_CONCEPTS" uri_exists "$ALL_CONCEPTS"
# --- per-study files -----------------------------------------------------
# Layout: {base}/{abv_lowercase}/data/{ABV_UPPERCASE}_PatientMapping.v2.csv
#         {base}/{abv_lowercase}/rawData/SSTR_*{studyId}*.txt (optional)
echo ""
echo "  Per-study inputs:"
SSTR_STUDIES=0
DIRECT_STUDIES=0

while IFS=$'\t' read -r abv sid state; do
  [[ "$state" != "ready" ]] && continue

  abv_lower=$(printf '%s' "$abv" | tr '[:upper:]' '[:lower:]')
  abv_upper=$(printf '%s' "$abv" | tr '[:lower:]' '[:upper:]')
  mapping="$DATA_FOLDER/${abv_lower}/data/${abv_upper}_PatientMapping.v2.csv"

  # Find SSTR file by listing the rawData directory for a file matching SSTR_*{studyId}*.txt
  raw_data_dir="$DATA_FOLDER/${abv_lower}/rawData"
  # Three naming families, all the same NHLBI artifact (canonical sstr_*, legacy
  # SSTR__sstr_* and BDC-ingestion-only__sstr_* folder-flattened copies); match
  # case-insensitively, preferring the canonical name -- mirrors the job's discovery.
  sstr_file=$(uri_ls "$raw_data_dir" 2>/dev/null | grep -iF "$sid" | grep -iE "^(sstr_|bdc-ingestion-only__sstr_).*\.txt$" \
              | awk '{ key = (tolower($0) ~ /^sstr_[^_]/) ? 0 : 1; print key "\t" $0 }' | sort | head -1 | cut -f2- || true)

  if [[ -n "$sstr_file" ]]; then
    SSTR_STUDIES=$((SSTR_STUDIES + 1))
    route="sstr"
    note "$sid ($abv): found SSTR file '$sstr_file'"
  else
    DIRECT_STUDIES=$((DIRECT_STUDIES + 1))
    route="direct"
  fi

  # Required on both routes; the sstr route needs it to emit old-hpds-id -> new-uuid pairs.
  check "$sid ($abv, $route): $mapping" uri_exists "$mapping"
done <<<"$STUDIES"

echo ""
note "$SSTR_STUDIES study/studies will load via the sstr sub-job, $DIRECT_STUDIES via direct population"
if (( DIRECT_STUDIES > 0 )); then
  note "the $DIRECT_STUDIES direct study/studies also need a GLOBAL_allConcepts_merged.csv row per legacy hpds id, "
  note "formatted {studyid}.c{code} -- ids with no row are skipped with a log warning only"
fi

summary
