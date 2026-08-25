#!/bin/bash
#
# Post-run validation for participants-migration, over the artifacts fetched from the
# runner's S3 report prefix.
#
# A participants-migration run is expected to leave, in the reports directory:
#   participants-migration-<runId>.json                       the JobResult
#   sstr-populate-rds-participants-<runId>-<study>-sstr.json   one per sstr-routed study
#   <studyId>_hpds_id_mapping.csv                              one per SUCCEEDED study
#   container-status.json                                      what was actually invoked
#
# Checking the mapping CSVs matters more than checking the exit code: the job deliberately
# treats a per-study data problem as a study-level failure and keeps going, and it skips
# individual unmatched ids with only a log warning. Both are invisible in the exit status.
#
# Usage: validate.sh <reports-dir> <run-id>
# Exit:  0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REPORTS="${1:-}"
RUN_ID="${2:-}"
if [[ -z "$REPORTS" || -z "$RUN_ID" ]]; then
  echo "usage: $0 <reports-dir> <run-id>" >&2
  exit 64
fi

REPORT="$REPORTS/participants-migration-$RUN_ID.json"
UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

# Folds a validate-report.sh run into this script's tally (0 clean / 10 warn / other fail).
fold_generic() {
  local label="$1" file="$2" rc
  "$HERE/../common/validate-report.sh" "$file"
  rc=$?
  case "$rc" in
    0)  echo "  [ok]   $label"; _PASS=$((_PASS + 1)) ;;
    10) echo "  [warn] $label (warnings above)"; _WARN=$((_WARN + 1)) ;;
    *)  echo "  [FAIL] $label"; _FAIL=$((_FAIL + 1)) ;;
  esac
}

echo "=========================================================="
echo " participants-migration validation (run $RUN_ID)"
echo "=========================================================="

fold_generic "standard report checks" "$REPORT"

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

# --- migration-specific metrics ------------------------------------------
READY=$(jq -r '.metrics.readyStudies      // 0' "$REPORT")
OK=$(jq -r    '.metrics.succeededStudies  // 0' "$REPORT")
BAD=$(jq -r   '.metrics.failedStudies     // 0' "$REPORT")

echo ""
echo "  Studies: $READY ready, $OK succeeded, $BAD failed"

check "at least one ready study was processed"   test "$READY" -gt 0
check "no study failed"                          test "$BAD" -eq 0
check "succeeded + failed accounts for every ready study" test "$((OK + BAD))" -eq "$READY"

if [[ "$BAD" != "0" ]]; then
  note "failed study ids: $(jq -r '(.metrics.failedStudyIds // []) | join(", ")' "$REPORT")"
  jq -r '(.outputValidation.issues // [])[] | select(.code == "STUDY_FAILED") | "         " + .message' "$REPORT"
fi

# One STUDY_MIGRATED info issue is recorded per successful study; comparing the two is a
# cheap cross-check that the metric and the issue list tell the same story.
MIGRATED_ISSUES=$(jq -r '[(.outputValidation.issues // [])[] | select(.code == "STUDY_MIGRATED")] | length' "$REPORT")
check "one STUDY_MIGRATED record per succeeded study" test "$MIGRATED_ISSUES" -eq "$OK"

# --- mapping CSVs --------------------------------------------------------
echo ""
echo "  Mapping files:"
# `mapfile` is bash 4+; this repo's scripts also get run on macOS, where /bin/bash is 3.2.
MAPPINGS=()
while IFS= read -r f; do
  [[ -n "$f" ]] && MAPPINGS+=("$f")
done < <(find "$REPORTS" -maxdepth 1 -name '*_hpds_id_mapping.csv' | sort)
check "one mapping CSV per succeeded study (found ${#MAPPINGS[@]})" test "${#MAPPINGS[@]}" -eq "$OK"

# ${arr[@]+...} guard: under `set -u`, bash 3.2 treats an empty array's expansion as unbound.
for csv in ${MAPPINGS[@]+"${MAPPINGS[@]}"}; do
  study=$(basename "$csv" _hpds_id_mapping.csv)
  header=$(head -1 "$csv")
  rows=$(($(wc -l < "$csv") - 1))

  check "$study: header is old_hpds_id,new_uuid,common_dbgap_id" \
    test "$header" = "old_hpds_id,new_uuid,common_dbgap_id"
  check "$study: has at least one mapping row" test "$rows" -gt 0

  bad_uuids=$(awk -F, -v re="$UUID_RE" 'NR>1 && $2 !~ re' "$csv" | grep -c . || true)
  check "$study: every new_uuid is a UUID ($rows row(s))" test "$bad_uuids" -eq 0
  [[ "$bad_uuids" != "0" ]] && note "$bad_uuids row(s) with a malformed uuid"

  # A duplicated legacy id would mean one old patient mapped to two new uuids -- the exact
  # corruption this migration exists to avoid.
  dupes=$(tail -n +2 "$csv" | cut -d, -f1 | sort | uniq -d | grep -c . || true)
  check "$study: no duplicated old_hpds_id" test "$dupes" -eq 0
  [[ "$dupes" != "0" ]] && note "duplicated old_hpds_id: $(tail -n +2 "$csv" | cut -d, -f1 | sort | uniq -d | head -5 | tr '\n' ' ')"

  blank=$(awk -F, 'NR>1 && ($1=="" || $2=="" || $3=="")' "$csv" | grep -c . || true)
  check "$study: no blank ids" test "$blank" -eq 0
done

# --- sstr sub-reports ----------------------------------------------------
# Studies with an sstr file are loaded by SstrPopulateRdsParticipantsJob, invoked in-process
# through JobExecutor, so each writes its own report next to the parent's.
echo ""
echo "  SSTR sub-runs:"
SUBREPORTS=()
while IFS= read -r f; do
  [[ -n "$f" ]] && SUBREPORTS+=("$f")
done < <(find "$REPORTS" -maxdepth 1 -name "sstr-populate-rds-participants-$RUN_ID-*-sstr.json" | sort)
if [[ ${#SUBREPORTS[@]} -eq 0 ]]; then
  note "none -- every ready study used the direct-population path"
fi

for sub in ${SUBREPORTS[@]+"${SUBREPORTS[@]}"}; do
  base=$(basename "$sub" -sstr.json)
  study="${base#sstr-populate-rds-participants-$RUN_ID-}"

  fold_generic "$study: sstr sub-report checks" "$sub"

  subjects=$(jq -r '.metrics.distinctParticipants // 0' "$sub")
  consents=$(jq -r '.metrics.consentsWritten      // 0' "$sub")
  rows_read=$(jq -r '.metrics.rowsRead            // 0' "$sub")

  # The job writes exactly one consent row per distinct subject, and upserts them with
  # ON CONFLICT DO UPDATE (always 1 row affected), so this is an invariant, not a heuristic.
  check "$study: consentsWritten == distinctParticipants ($consents == $subjects)" \
    test "$consents" -eq "$subjects"
  check "$study: read at least one row" test "$rows_read" -gt 0

  # Unmatched patient-mapping ids are skipped with a log warning only. The mapping file has
  # one row per RESOLVED patient-mapping row, so it can legitimately exceed the subject count
  # (several legacy ids per subject) -- but falling short of it is the visible symptom of
  # patients silently dropped from the migration.
  csv="$REPORTS/${study}_hpds_id_mapping.csv"
  if [[ -f "$csv" ]]; then
    mapped=$(($(wc -l < "$csv") - 1))
    soft "$study: mapping rows ($mapped) cover the $subjects sstr subject(s)" test "$mapped" -ge "$subjects"
    (( mapped < subjects )) && note "$((subjects - mapped)) fewer mapping row(s) than sstr subjects -- check the log for 'not found in sstr file' / 'no participant uuid found' warnings"
  fi
done

summary
