#!/bin/bash
#
# Post-run validation for qa-pipeline-counts, over the artifacts fetched from the runner's S3
# report prefix:
#   qa-pipeline-counts-<runId>.json     the JobResult
#   qa_pipeline_counts.csv              one row per study, consent code and count source
#   qa_pipeline_discrepancies.csv       its rows needing follow-up
#   qa_pipeline_studies.csv             one row per study
#   qa-pipeline-report.md               the same, to read
#
# Prints every discrepancy, so the console names what to look at. Discrepancies are warnings
# (exit 10: the build goes UNSTABLE), among them a study with no summary file when fetching missing
# summaries is off (SUMMARY_MISSING). So are studies dbGaP did not answer for when it is on, as long
# as some study had a summary; none having one is then a failure.
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

REPORT="$REPORTS/qa-pipeline-counts-$RUN_ID.json"
COUNTS="$REPORTS/qa_pipeline_counts.csv"
DISCREPANCIES="$REPORTS/qa_pipeline_discrepancies.csv"
STUDIES="$REPORTS/qa_pipeline_studies.csv"
MARKDOWN="$REPORTS/qa-pipeline-report.md"
HEADER="study_id,version,in_run,consent_code,consent_short_name,measure,compared_to,dbgap_count,count,status,note"

echo "=========================================================="
echo " qa-pipeline-counts validation (run $RUN_ID)"
echo "=========================================================="

"$HERE/../common/validate-report.sh" "$REPORT"
case $? in
  0)  echo "  [ok]   standard report checks"; _PASS=$((_PASS + 1)) ;;
  10) echo "  [warn] standard report checks (warnings above)"; _WARN=$((_WARN + 1)) ;;
  *)  echo "  [FAIL] standard report checks"; _FAIL=$((_FAIL + 1)) ;;
esac

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

metric() { jq -r ".metrics.$1 // empty | if type == \"array\" then length else . end" "$REPORT"; }
note "pipeline $(jq -r '.metrics.pipeline // "none"' "$REPORT"): studies checked $(metric studiesChecked)" \
     "($(metric studiesInRun) in this run), verified $(metric studiesVerified); counts compared" \
     "$(metric countsCompared), matching $(metric countsMatching)"
note "discrepancies $(metric discrepancies): $(jq -r '(.metrics.discrepanciesByType // {}) | to_entries | map("\(.key) \(.value)") | join(", ") | if . == "" then "none" else . end' "$REPORT")"
note "fetching missing summaries from dbGaP: $(jq -r '.metrics.fetchMissingSummaries // false' "$REPORT")"
note "summaries: $(metric summariesFromCache) cached, $(metric summariesFromApi) fetched ($(metric summariesCached) newly cached;" \
     "$(metric apiAttempts) dbGaP requests, $(metric apiRetries) retries)"
note "run counts: $(jq -r '.metrics.runCounts // "none"' "$REPORT") -- sources: $(jq -r '(.metrics.runCountSources // []) | join(", ") | if . == "" then "none" else . end' "$REPORT")"

UNVERIFIED=$(jq -r '(.metrics.unverifiedStudies // []) | join(", ")' "$REPORT")
CACHE_FAILURES=$(jq -r '(.metrics.summaryCacheFailures // {}) | keys | join(", ")' "$REPORT")
soft "every study had a summary${UNVERIFIED:+ (not: $UNVERIFIED -- a later run fetches them)}" test -z "$UNVERIFIED"
soft "every fetched summary was cached${CACHE_FAILURES:+ (not: $CACHE_FAILURES)}" test -z "$CACHE_FAILURES"

for f in "$COUNTS" "$DISCREPANCIES" "$STUDIES" "$MARKDOWN"; do
  check "$(basename "$f") written" test -f "$f"
done
if [[ -f "$COUNTS" && -f "$DISCREPANCIES" ]]; then
  check "counts header" test "$(head -1 "$COUNTS")" = "$HEADER"
  check "discrepancies header" test "$(head -1 "$DISCREPANCIES")" = "$HEADER"
  # python's csv module, not cut: a note may hold a quoted comma.
  read -r FOLLOW_UP LISTED < <(python3 - "$COUNTS" "$DISCREPANCIES" <<'PY'
import csv, sys
def rows(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))
print(sum(1 for r in rows(sys.argv[1]) if r["status"] not in ("MATCH", "NOT_LOADED_BY_DESIGN")), len(rows(sys.argv[2])))
PY
  )
  check "discrepancies are exactly the rows needing follow-up (${FOLLOW_UP:-?} vs ${LISTED:-?})" \
    test -n "${FOLLOW_UP:-}" -a "${FOLLOW_UP:-x}" = "${LISTED:-y}"
  if [[ "${LISTED:-0}" -gt 0 ]]; then
    echo ""
    echo "  Needing follow-up (study version consent, source: count vs dbGaP, status -- note):"
    python3 - "$DISCREPANCIES" <<'PY'
import csv, sys
with open(sys.argv[1], newline="", encoding="utf-8") as fh:
    rows = list(csv.DictReader(fh))
for r in rows[:200]:
    print(f"         {r['study_id']} {r['version']} c{r['consent_code']} {r['consent_short_name']}, "
          f"{r['measure']}: {r['count'] or '-'} vs {r['dbgap_count'] or '?'} {r['status']}"
          f"{' -- ' + r['note'] if r['note'] else ''}")
if len(rows) > 200:
    print(f"         ... and {len(rows) - 200} more in {sys.argv[1]}")
PY
  fi
  soft "every count matches dbGaP" test "${LISTED:-1}" -eq 0
fi

summary
