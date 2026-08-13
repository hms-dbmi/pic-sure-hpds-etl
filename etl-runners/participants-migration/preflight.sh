#!/bin/bash
#
# Pre-flight checks for the participants-migration job, run on the Jenkins agent BEFORE any
# EC2 instance is provisioned.
#
# ParticipantsMigrationJob discovers its per-study files by convention and treats a missing
# or malformed file as a per-study failure at run time. Every one of those failures is
# knowable from the input layout alone, so checking here turns a 20-minute partial
# migration into a 20-second console message and costs no instance time.
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
DATA_FOLDER="${TF_VAR_data_folder_uri:?TF_VAR_data_folder_uri is required}"
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
check "managed-inputs exists" uri_exists "$MANAGED_INPUTS"
if ! uri_exists "$MANAGED_INPUTS"; then
  summary
  exit 1
fi

MANIFEST=$(mktemp)
trap 'rm -f "$MANIFEST"' EXIT
read_uri "$MANAGED_INPUTS" > "$MANIFEST"

# Parsed with python3's csv module rather than awk: the study list is a hand-maintained
# spreadsheet export, so quoted fields containing commas are normal. python3 is already a
# hard dependency of the BDC ETL agents.
STUDIES=$(python3 - "$MANIFEST" <<'PY'
import csv, sys

ABV, SID, READY = "Study Abbreviated Name", "Study Identifier", "Data is ready to process"

with open(sys.argv[1], newline="", encoding="utf-8-sig") as fh:
    reader = csv.DictReader(fh)
    missing = [c for c in (ABV, SID, READY) if c not in (reader.fieldnames or [])]
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
  note "expected: 'Study Abbreviated Name', 'Study Identifier', 'Data is ready to process'"
  summary
  exit 1
fi

check "managed-inputs has the three required columns" true

READY_COUNT=$(awk -F'\t' '$3=="ready"' <<<"$STUDIES" | grep -c . || true)
check "at least one study is marked ready" test "$READY_COUNT" -gt 0
note "$READY_COUNT of $(grep -c . <<<"$STUDIES") studies marked ready to process"

# --- shared consents.csv -------------------------------------------------
# Required unconditionally: execute() reads consents.csv before it looks at a single study,
# so a missing one aborts the ENTIRE run with a config error -- not just the studies that
# would have used it for direct population.
CONSENTS="$DATA_FOLDER/consents.csv"
check "shared consents.csv exists (read before any study is processed)" uri_exists "$CONSENTS"

# --- per-study files -----------------------------------------------------
echo ""
echo "  Per-study inputs:"
SSTR_STUDIES=0
DIRECT_STUDIES=0

while IFS=$'\t' read -r abv sid state; do
  [[ "$state" != "ready" ]] && continue

  sstr="$DATA_FOLDER/${sid}_sstr.tsv"
  mapping="$DATA_FOLDER/$(printf '%s' "$abv" | tr '[:lower:]' '[:upper:]')_PatientMapping.v2.csv"

  if uri_exists "$sstr"; then
    SSTR_STUDIES=$((SSTR_STUDIES + 1))
    route="sstr"
  else
    DIRECT_STUDIES=$((DIRECT_STUDIES + 1))
    route="direct"
  fi

  # The mapping file is required on both routes: the sstr route still needs it to emit
  # old-hpds-id -> new-uuid pairs.
  check "$sid ($abv, $route): ${mapping##*/}" uri_exists "$mapping"
done <<<"$STUDIES"

echo ""
note "$SSTR_STUDIES study/studies will load via the sstr sub-job, $DIRECT_STUDIES via direct population"
if (( DIRECT_STUDIES > 0 )); then
  note "the $DIRECT_STUDIES direct study/studies also need a consents.csv row per legacy hpds id, "
  note "formatted {studyid}.c{code} -- ids with no row are skipped with a log warning only"
fi

summary
