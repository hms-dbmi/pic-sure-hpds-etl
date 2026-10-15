#!/bin/bash
#
# Post-run validation for participants-migration, over the artifacts fetched from the
# runner's S3 report prefix.
#
# A participants-migration run is expected to leave, in the reports directory:
#   participants-migration-<runId>.json                       the JobResult
#   sstr-populate-rds-participants-<runId>-<study>-sstr.json   one per study with an SSTR
#   multi-populate-rds-participants-<runId>-<study>-multi.json one per study that fell back to MULTI
#   <studyId>_hpds_id_mapping.csv                              one per SUCCEEDED study, and a
#                                                              header-only one per study flagged
#                                                              STUDY_NEEDS_DATA_REVIEW
#   participants_migration_sources.csv                         one row per ready study: source used
#   participants_migration_file_issues.csv                     problems found in the source files
#   participants_migration_file_report.md                      the two above, human-readable
#   studies/<studyId>.json                                     one per ready study, written as it finished
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
INTEGER_RE='^[0-9]+$'

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
SKIPPED=$(jq -r '.metrics.skippedStudies   // 0' "$REPORT")

echo ""
echo "  Studies: $READY ready, $OK succeeded, $SKIPPED skipped, $BAD failed"

check "at least one ready study was processed"   test "$READY" -gt 0
check "no study failed"                          test "$BAD" -eq 0
check "succeeded + skipped + failed accounts for every ready study" test "$((OK + SKIPPED + BAD))" -eq "$READY"
# A skipped study is not migrated, but it does not fail the run: it is a warning (UNSTABLE), so
# the pipeline carries on without it and the study is listed for follow-up. Two reasons:
#  - no participant files at all (STUDY_SKIPPED);
#  - its participants loaded but not one patient mapping row resolved to them, so its mapping
#    file is empty (STUDY_NEEDS_DATA_REVIEW). Read from the report's issues, not inferred from
#    the CSV, so an empty mapping file is only accepted for a study the job flagged.
REVIEW_IDS=$(jq -r '[(.outputValidation.issues // [])[] | select(.code == "STUDY_NEEDS_DATA_REVIEW")
    | .context.studyId] | join(",")' "$REPORT")
REVIEW=0
[[ -n "$REVIEW_IDS" ]] && REVIEW=$(echo "$REVIEW_IDS" | tr ',' '\n' | wc -l | tr -d ' ')
check "every study flagged for data review is counted as skipped ($REVIEW <= $SKIPPED)" test "$REVIEW" -le "$SKIPPED"
soft "no ready study was skipped for lack of participant files" test "$((SKIPPED - REVIEW))" -eq 0
if [[ "$((SKIPPED - REVIEW))" != "0" ]]; then
  note "skipped, not migrated (no SSTR and no Subject.MULTI in rawData): $(jq -r --arg r ",$REVIEW_IDS," \
    '[(.metrics.skippedStudyIds // [])[] | . as $id | select($r | contains("," + $id + ",") | not)] | join(", ")' "$REPORT")"
fi
soft "no ready study needs data review" test "$REVIEW" -eq 0
if [[ "$REVIEW" != "0" ]]; then
  note "skipped, not migrated, needs data review (no patient mapping row resolved):"
  jq -r '(.outputValidation.issues // [])[] | select(.code == "STUDY_NEEDS_DATA_REVIEW") | "         " + .message' "$REPORT"
fi

if [[ "$BAD" != "0" ]]; then
  note "failed study ids: $(jq -r '(.metrics.failedStudyIds // []) | join(", ")' "$REPORT")"
  jq -r '(.outputValidation.issues // [])[] | select(.code == "STUDY_FAILED") | "         " + .message' "$REPORT"
fi

# --- participant sources -------------------------------------------------
# SSTR always wins; MULTI is the fallback. A study with neither already counted as failed above.
echo ""
echo "  Participant sources:"
note "sstr:  $(jq -r '(.metrics.sstrStudyIds  // []) | length' "$REPORT") study/studies"
note "multi: $(jq -r '(.metrics.multiStudyIds // []) | length' "$REPORT") study/studies"
REUSED=$(jq -r '(.metrics.reusedStudyIds // []) | length' "$REPORT")
# A reused study was migrated by the run given as RESUME_FROM_RUN_ID; this run only copied its
# mapping file, so it has no sstr/multi sub-report below.
[[ "$REUSED" != "0" ]] && note "copied from an earlier run (--resume-from): $REUSED study/studies"
FELL_BACK=$(jq -r '(.metrics.sstrFallbackStudyIds // []) | join(",")' "$REPORT")
soft "no study had a malformed SSTR" test -z "$FELL_BACK"
[[ -n "$FELL_BACK" ]] && note "malformed SSTR: $FELL_BACK"

SOURCES="$REPORTS/participants_migration_sources.csv"
ISSUES="$REPORTS/participants_migration_file_issues.csv"
check "sources report present" test -f "$SOURCES"
check "file issues report present" test -f "$ISSUES"
if [[ -f "$SOURCES" ]]; then
  check "one sources row per ready study" test "$(($(wc -l < "$SOURCES") - 1))" -eq "$READY"
fi
STUDY_RECORDS=$(find "$REPORTS/studies" -maxdepth 1 -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
check "one studies/<id>.json per ready study (found $STUDY_RECORDS)" test "$STUDY_RECORDS" -eq "$READY"
if [[ -f "$ISSUES" ]]; then
  # Severity is the second column; the first (study id) never contains a comma.
  issue_warn=$(tail -n +2 "$ISSUES" | cut -d, -f2 | grep -c '^WARNING$' || true)
  issue_err=$(tail -n +2 "$ISSUES" | cut -d, -f2 | grep -c '^ERROR$' || true)
  note "file issues: $issue_err error(s), $issue_warn warning(s) -- see participants_migration_file_report.md"
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
check "one mapping CSV per succeeded or needs-review study (found ${#MAPPINGS[@]})" \
  test "${#MAPPINGS[@]}" -eq "$((OK + REVIEW))"

# Every CSV is measured in ONE awk pass: with ~500 studies, the dozen processes per file this
# used to start were most of validation's run time. Per file, awk prints (\037-separated, so an
# empty field survives `read`): study, header, data rows, malformed new_hpds_ids, duplicated
# old_hpds_ids, the first five of those, rows with a blank id. Blank lines are not rows.
US=$'\037'
# ${arr[@]+...} guard: under `set -u`, bash 3.2 treats an empty array's expansion as unbound.
while IFS="$US" read -r study header rows bad_ids dupes dupe_examples blank; do
  check "$study: header is old_hpds_id,new_hpds_id,common_dbgap_id" \
    test "$header" = "old_hpds_id,new_hpds_id,common_dbgap_id"
  if [[ ",$REVIEW_IDS," == *",$study,"* ]]; then
    # Flagged STUDY_NEEDS_DATA_REVIEW above: no row resolved, so an empty file is the expected one.
    check "$study: no mapping rows, as expected for a study needing data review" test "$rows" -eq 0
  else
    check "$study: has at least one mapping row" test "$rows" -gt 0
  fi

  check "$study: every new_hpds_id is an integer ($rows row(s))" test "$bad_ids" -eq 0
  [[ "$bad_ids" != "0" ]] && note "$bad_ids row(s) with a malformed hpds_id"

  # A duplicated legacy id would mean one old patient mapped to two new hpds ids -- the exact
  # corruption this migration exists to avoid.
  check "$study: no duplicated old_hpds_id" test "$dupes" -eq 0
  [[ "$dupes" != "0" ]] && note "duplicated old_hpds_id: $dupe_examples"

  check "$study: no blank ids" test "$blank" -eq 0
done < <(
  [[ ${#MAPPINGS[@]} -eq 0 ]] || awk -v re="$INTEGER_RE" -v us="$US" '
    BEGIN {
      for (i = 1; i < ARGC; i++) {
        f = ARGV[i]
        study = f; sub(/^.*\//, "", study); sub(/_hpds_id_mapping\.csv$/, "", study)
        header = ""; rows = 0; bad = 0; dupes = 0; examples = ""; blank = 0; first = 1
        split("", seen)
        while ((getline line < f) > 0) {
          if (first) { header = line; first = 0; continue }
          if (line == "") continue
          rows++
          n = split(line, col, ",")
          if (col[2] !~ re) bad++
          if (col[1] == "" || col[2] == "" || col[3] == "") blank++
          # A blank old id is reported by the blank-id check, not as a duplicate.
          if (col[1] != "" && seen[col[1]]++ == 1) { dupes++; if (dupes <= 5) examples = examples col[1] " " }
        }
        close(f)
        print study us header us rows us bad us dupes us examples us blank
      }
      exit
    }' ${MAPPINGS[@]+"${MAPPINGS[@]}"}
)

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
  note "none -- no ready study had an SSTR"
fi

for sub in ${SUBREPORTS[@]+"${SUBREPORTS[@]}"}; do
  base=$(basename "$sub" -sstr.json)
  study="${base#sstr-populate-rds-participants-$RUN_ID-}"

  # A malformed SSTR's failed sub-report is expected when the study fell back to MULTI; the
  # MULTI sub-report below is the one that has to pass.
  if [[ ",$FELL_BACK," == *",$study,"* ]]; then
    note "$study: sstr sub-run failed ($(jq -r '.errorMessage // ""' "$sub")); fell back to MULTI"
    continue
  fi

  fold_generic "$study: sstr sub-report checks" "$sub"

  # Left empty for an unreadable report, so the checks below fail rather than pass on 0 == 0.
  subjects=; consents=; rows_read=
  IFS=$'\t' read -r subjects consents rows_read < <(jq -r '.metrics | [
      .distinctParticipants // 0, .consentsWritten // 0, .rowsRead // 0] | @tsv' "$sub" 2>/dev/null) || true

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
  if [[ ",$REVIEW_IDS," == *",$study,"* ]]; then
    note "$study: no mapping rows (needs data review, reported above)"
  elif [[ -f "$csv" ]]; then
    mapped=$(($(wc -l < "$csv") - 1))
    soft "$study: mapping rows ($mapped) cover the $subjects sstr subject(s)" test "$mapped" -ge "$subjects"
    (( mapped < subjects )) && note "$((subjects - mapped)) fewer mapping row(s) than sstr subjects -- check the log for 'not found in sstr file' / 'no participant id found' warnings"
  fi
done

# --- MULTI sub-reports ---------------------------------------------------
# Studies with no usable SSTR are loaded by MultiPopulateRdsParticipantsJob, invoked the same way.
echo ""
echo "  MULTI sub-runs:"
MULTI_REPORTS=()
while IFS= read -r f; do
  [[ -n "$f" ]] && MULTI_REPORTS+=("$f")
done < <(find "$REPORTS" -maxdepth 1 -name "multi-populate-rds-participants-$RUN_ID-*-multi.json" | sort)
if [[ ${#MULTI_REPORTS[@]} -eq 0 ]]; then
  note "none -- no ready study fell back to its MULTI files"
fi

for sub in ${MULTI_REPORTS[@]+"${MULTI_REPORTS[@]}"}; do
  base=$(basename "$sub" -multi.json)
  study="${base#multi-populate-rds-participants-$RUN_ID-}"

  fold_generic "$study: multi sub-report checks" "$sub"

  subjects=; consents=; dropped=; samples_loaded=
  IFS=$'\t' read -r subjects consents dropped samples_loaded < <(jq -r '.metrics | [
      .distinctParticipants // 0, .consentsWritten // 0, .notConsentedSubjects // 0,
      .sampleFileLoaded // false] | @tsv' "$sub" 2>/dev/null) || true
  check "$study: consentsWritten == distinctParticipants ($consents == $subjects)" \
    test "$consents" -eq "$subjects"
  check "$study: loaded at least one consented subject" test "$subjects" -gt 0
  [[ "$dropped" != "0" ]] && note "$study: $dropped consent-0 subject(s) dropped"
  [[ "${samples_loaded:-false}" != "true" ]] && note "$study: no samples loaded (no usable Sample.MULTI)"
done

summary
