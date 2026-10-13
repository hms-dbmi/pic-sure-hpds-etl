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

# --- per-study files -----------------------------------------------------
# Layout: {base}/{studyId}/legacy/data/{ABV_UPPERCASE}_PatientMapping.v2.csv
#         {base}/{studyId}/rawData/sstr_*{studyId}*.txt                 (preferred source)
#         {base}/{studyId}/rawData/{studyid}*Subject.MULTI.txt           (fallback source)
#         {base}/{studyId}/rawData/{studyid}*Sample.MULTI.txt            (optional, fallback only)
#         {base}/{studyId}/rawData/{studyid}*Subject.data_dict.xml       (optional, fallback only)
# A study with neither an SSTR nor a Subject.MULTI fails at run time. That is a warning here,
# not a failure: the job still migrates every other study and reports the ones it could not.
echo ""
echo "  Per-study inputs:"
SSTR_STUDIES=0
MULTI_STUDIES=0
NO_SOURCE_STUDIES=0

while IFS=$'\t' read -r abv sid state; do
  [[ "$state" != "ready" ]] && continue

  abv_upper=$(printf '%s' "$abv" | tr '[:lower:]' '[:upper:]')
  mapping="$DATA_FOLDER/${sid}/legacy/data/${abv_upper}_PatientMapping.v2.csv"

  raw_data_dir="$DATA_FOLDER/${sid}/rawData"
  listing=$(uri_ls "$raw_data_dir" 2>/dev/null || true)
  # Three naming families, all the same NHLBI artifact (canonical sstr_*, legacy
  # SSTR__sstr_* and BDC-ingestion-only__sstr_* folder-flattened copies); match
  # case-insensitively, preferring the canonical name -- mirrors the job's discovery.
  sstr_file=$(grep -iF "$sid" <<<"$listing" | grep -iE "^(sstr_|bdc-ingestion-only__sstr_).*\.txt$" \
              | awk '{ key = (tolower($0) ~ /^sstr_[^_]/) ? 0 : 1; print key "\t" $0 }' | sort | head -1 | cut -f2- || true)
  # ParticipantsMigrationJob.isSubjectMultiFileFor / isSampleMultiFileFor / isSubjectDataDictFor.
  # Only presence matters here; when several match, the job picks the newest and reports it.
  subject_multi=$(grep -iE "^${sid}.*subject\.multi\.txt$" <<<"$listing" | head -1 || true)
  sample_multi=$(grep -iE "^${sid}.*sample\.multi\.txt$" <<<"$listing" | head -1 || true)
  data_dict=$(grep -iE "^${sid}.*subject\.data_dict\.xml$" <<<"$listing" | head -1 || true)

  if [[ -n "$sstr_file" ]]; then
    SSTR_STUDIES=$((SSTR_STUDIES + 1))
    route="sstr"
    note "$sid ($abv): found SSTR file '$sstr_file'"
    [[ -z "$subject_multi" ]] && note "$sid ($abv): no Subject.MULTI fallback -- the study fails if its SSTR is malformed"
  elif [[ -n "$subject_multi" ]]; then
    MULTI_STUDIES=$((MULTI_STUDIES + 1))
    route="multi"
    note "$sid ($abv): no SSTR; will load from '$subject_multi'"
    [[ -z "$sample_multi" ]] && note "$sid ($abv): no Sample.MULTI file -- no samples will be loaded"
    [[ -z "$data_dict" ]] && note "$sid ($abv): no Subject.data_dict.xml -- consent abbreviations will be blank"
  else
    NO_SOURCE_STUDIES=$((NO_SOURCE_STUDIES + 1))
    route="none"
    warn "$sid ($abv): no SSTR and no Subject.MULTI in $raw_data_dir -- this study will fail"
  fi

  # Required on every route; it supplies the old-hpds-id -> new-hpds-id pairs.
  check "$sid ($abv, $route): $mapping" uri_exists "$mapping"
done <<<"$STUDIES"

echo ""
note "$SSTR_STUDIES study/studies will load from their SSTR, $MULTI_STUDIES from their MULTI files, "
note "$NO_SOURCE_STUDIES have no participant source and will fail"

summary
