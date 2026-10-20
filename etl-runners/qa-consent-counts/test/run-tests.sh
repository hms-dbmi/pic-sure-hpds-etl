#!/bin/bash
# Tests for the qa-consent-counts runner's scripts. No AWS, Jenkins or JVM needed.
#
#   1. build-load-counts.py turns the SSTR load reports into the --load-counts CSV: successful
#      loads only, newest report per study, header even when empty.
#   2. validate.sh exits 0 when every consent matches, 10 on discrepancies (and lists them), and 1
#      when dbGaP could not answer (exit 4), the report is missing, or the CSVs disagree.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$(cd "$HERE/.." && pwd)"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/qa-consent-counts-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FAILED=0

pass() { echo "  [ok]   $*"; }
fail() { echo "  [FAIL] $*"; FAILED=$((FAILED + 1)); }

sstr_report() {   # <dir> <study> <run> <status> <exitCode> <countsJson>
  local d="$1/$2/etl-runners/sstr-populate-rds-participants/reports"
  mkdir -p "$d"
  printf '{"jobName":"sstr-populate-rds-participants","runId":"%s","status":"%s","exitCode":"%s","metrics":{"countsByConsentGroup":%s}}\n' \
    "$3" "$4" "$5" "$6" > "$d/sstr-populate-rds-participants-$3.json"
  echo "$d/sstr-populate-rds-participants-$3.json"
}

echo "build-load-counts.py"
S="$TMP/sstr"
sstr_report "$S" phs000001 t-phs000001 SUCCESS SUCCESS '{"1":402,"2":3}' >/dev/null
sstr_report "$S" phs000002 t-phs000002 FAILED DATA_ERROR '{"1":9}' >/dev/null
old=$(sstr_report "$S" phs000003 t-phs000003 SUCCESS SUCCESS '{"1":1}')
touch -t 202001010000 "$old"
sstr_report "$S" phs000003 t-phs000003-r2 SUCCESS SUCCESS_WITH_WARNINGS '{"c1":7}' >/dev/null
mkdir -p "$S/phs000004" "$S/not-a-study"
if python3 "$RUNNER/build-load-counts.py" "$S" "$TMP/out/load.csv" >"$TMP/build.log" 2>&1; then
  pass "written"
else
  fail "exited $? ($(cat "$TMP/build.log"))"
fi
expected='study_id,consent_code,participants_loaded,source
phs000001,1,402,sstr
phs000001,2,3,sstr
phs000003,c1,7,sstr'
[ "$(cat "$TMP/out/load.csv")" = "$expected" ] \
  && pass "successful loads only, newest report per study" || fail "got: $(cat "$TMP/out/load.csv")"
grep -q 'no load counts for 2 study/studies: phs000002 (DATA_ERROR); phs000004 (no readable report' "$TMP/build.log" \
  && pass "names the studies it left out" || fail "log: $(cat "$TMP/build.log")"
python3 "$RUNNER/build-load-counts.py" "$TMP/absent" "$TMP/empty.csv" >/dev/null 2>&1
[ "$(cat "$TMP/empty.csv")" = "study_id,consent_code,participants_loaded,source" ] \
  && pass "no SSTR reports: header only" || fail "empty: $(cat "$TMP/empty.csv" 2>&1)"
python3 "$RUNNER/build-load-counts.py" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo "validate.sh"
HEADER="study_id,version,consent_code,consent_short_name,dbgap_subjects,db_participants,load_participants,load_source,status,note"
qa_run() {   # <dir> <exitCode> <status> <warnings> <unverifiedJson> <countsRows> <discrepancyRows>
  mkdir -p "$1"
  local issues='[]'
  [ "$4" -gt 0 ] && issues='[{"severity":"WARNING","code":"COUNT_MISMATCH","message":"phs000001.v4 consent 2: dbGaP 3, DB 2"}]'
  printf '{"jobName":"qa-consent-counts","jobType":"PERMANENT","runId":"r","exitCode":"%s","status":"%s","durationMillis":1,"inputValidation":{"issues":[],"counts":{"error":0,"warning":0,"info":0}},"outputValidation":{"issues":%s,"counts":{"error":0,"warning":%s,"info":0}},"metrics":{"studiesChecked":1,"studiesVerified":1,"consentsCompared":2,"consentsMatching":1,"discrepancies":%s,"discrepanciesByType":{},"unverifiedStudies":%s,"apiAttempts":1,"apiRetries":0},"errorMessage":%s}\n' \
    "$2" "$3" "$issues" "$4" "$4" "$5" "$([ "$3" = SUCCESS ] && echo null || echo '"dbGaP down"')" > "$1/qa-consent-counts-r.json"
  printf '%s\n%b' "$HEADER" "$6" > "$1/qa_consent_counts.csv"
  printf '%s\n%b' "$HEADER" "$7" > "$1/qa_consent_count_discrepancies.csv"
}
expect_rc() {   # <label> <want> <dir>
  "$RUNNER/validate.sh" "$3" r >"$TMP/v.log" 2>&1
  local rc=$?
  [ "$rc" -eq "$2" ] && pass "$1 (exit $rc)" || { fail "$1: exit $rc, want $2"; cat "$TMP/v.log"; }
}
MATCH='phs000001,v4,1,G1,402,402,,,MATCH,\n'
MISMATCH='phs000001,v4,2,G2,3,2,2,multi,COUNT_MISMATCH,"a note, with a comma"\n'
qa_run "$TMP/ok" SUCCESS SUCCESS 0 '[]' "$MATCH" ''
expect_rc "every consent matching passes" 0 "$TMP/ok"
qa_run "$TMP/warn" SUCCESS_WITH_WARNINGS SUCCESS 1 '[]' "$MATCH$MISMATCH" "$MISMATCH"
expect_rc "discrepancies are warnings" 10 "$TMP/warn"
grep -q 'phs000001 v4 c2 G2: dbGaP 3 / DB 2 / load 2 COUNT_MISMATCH -- a note, with a comma' "$TMP/v.log" \
  && pass "lists each discrepancy" || { fail "discrepancy not listed"; cat "$TMP/v.log"; }
qa_run "$TMP/down" INFRASTRUCTURE_ERROR FAILED 0 '["phs000002"]' "$MATCH" ''
expect_rc "dbGaP not answering fails" 1 "$TMP/down"
grep -q 'not for: phs000002' "$TMP/v.log" && pass "names the unverified study" || fail "unverified study not named"
qa_run "$TMP/disagree" SUCCESS_WITH_WARNINGS SUCCESS 1 '[]' "$MATCH$MISMATCH" ''
expect_rc "a discrepancy file missing a non-MATCH row fails" 1 "$TMP/disagree"
mkdir -p "$TMP/none"
expect_rc "a missing report fails" 1 "$TMP/none"
"$RUNNER/validate.sh" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
