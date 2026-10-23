#!/bin/bash
#
# Post-run validation for qa-consent-counts, over the artifacts fetched from the runner's S3
# report prefix:
#   qa-consent-counts-<runId>.json          the JobResult
#   qa_consent_counts.csv                   one row per study and consent code
#   qa_consent_count_discrepancies.csv      its rows whose status is not MATCH
#
# Prints every discrepancy, so the console names what to look at. Discrepancies are warnings
# (exit 10: the build goes UNSTABLE), and so are studies dbGaP could not answer for, as long as it
# answered for at least one; answering for none is a failure.
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

REPORT="$REPORTS/qa-consent-counts-$RUN_ID.json"
COUNTS="$REPORTS/qa_consent_counts.csv"
DISCREPANCIES="$REPORTS/qa_consent_count_discrepancies.csv"
HEADER="study_id,version,consent_code,consent_short_name,dbgap_subjects,db_participants,load_participants,load_source,status,note"

echo "=========================================================="
echo " qa-consent-counts validation (run $RUN_ID)"
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
note "studies checked $(metric studiesChecked), verified against dbGaP $(metric studiesVerified); " \
     "consents compared $(metric consentsCompared), matching $(metric consentsMatching)"
note "discrepancies $(metric discrepancies): $(jq -r '(.metrics.discrepanciesByType // {}) | to_entries | map("\(.key) \(.value)") | join(", ") | if . == "" then "none" else . end' "$REPORT")"
note "dbGaP requests $(metric apiAttempts) ($(metric apiRetries) retries); load counts: $(jq -r '.metrics.loadCounts // "none"' "$REPORT")"

UNVERIFIED=$(jq -r '(.metrics.unverifiedStudies // []) | join(", ")' "$REPORT")
ANSWERED=$(jq -r '.metrics.studiesAnswered // 0' "$REPORT")
if [[ -n "$UNVERIFIED" ]]; then
  check "dbGaP answered for at least one study ($ANSWERED)" test "$ANSWERED" -gt 0
fi
soft "dbGaP answered for every study${UNVERIFIED:+ (not for: $UNVERIFIED -- re-run QA for them)}" test -z "$UNVERIFIED"

check "consent counts written ($(basename "$COUNTS"))" test -f "$COUNTS"
check "discrepancies written ($(basename "$DISCREPANCIES"))" test -f "$DISCREPANCIES"
if [[ -f "$COUNTS" && -f "$DISCREPANCIES" ]]; then
  check "consent counts header" test "$(head -1 "$COUNTS")" = "$HEADER"
  check "discrepancies header" test "$(head -1 "$DISCREPANCIES")" = "$HEADER"
  # python's csv module, not cut: a note may hold a quoted comma.
  read -r NON_MATCH LISTED < <(python3 - "$COUNTS" "$DISCREPANCIES" <<'PY'
import csv, sys
def rows(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))
print(sum(1 for r in rows(sys.argv[1]) if r["status"] != "MATCH"), len(rows(sys.argv[2])))
PY
  )
  check "discrepancies are exactly the non-MATCH rows (${NON_MATCH:-?} vs ${LISTED:-?})" \
    test -n "${NON_MATCH:-}" -a "${NON_MATCH:-x}" = "${LISTED:-y}"
  if [[ "${LISTED:-0}" -gt 0 ]]; then
    echo ""
    echo "  Discrepancies (study, version, consent, dbGaP / DB / load, status, note):"
    python3 - "$DISCREPANCIES" <<'PY'
import csv, sys
with open(sys.argv[1], newline="", encoding="utf-8") as fh:
    rows = list(csv.DictReader(fh))
for r in rows[:200]:
    print(f"         {r['study_id']} {r['version']} c{r['consent_code']} {r['consent_short_name']}: "
          f"dbGaP {r['dbgap_subjects'] or '-'} / DB {r['db_participants']} / load {r['load_participants'] or '-'} "
          f"{r['status']}{' -- ' + r['note'] if r['note'] else ''}")
if len(rows) > 200:
    print(f"         ... and {len(rows) - 200} more in {sys.argv[1]}")
PY
  fi
  soft "every consent count matches dbGaP and the load reports" test "${LISTED:-1}" -eq 0
fi

summary
