#!/bin/bash
# Tests for the qa-consent-counts runner's scripts. No AWS, Jenkins or JVM needed.
#
#   1. validate.sh exits 0 when every consent matches, 10 on discrepancies or studies dbGaP did not
#      answer for (and lists them), and 1 when dbGaP answered for none (exit 4), the report is
#      missing, or the CSVs disagree.
#   2. preflight.sh needs exactly one of MANAGED_INPUTS and STUDIES.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$(cd "$HERE/.." && pwd)"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/qa-consent-counts-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FAILED=0

pass() { echo "  [ok]   $*"; }
fail() { echo "  [FAIL] $*"; FAILED=$((FAILED + 1)); }

echo "validate.sh"
HEADER="study_id,version,consent_code,consent_short_name,dbgap_subjects,db_participants,load_participants,load_source,status,note"
qa_run() {   # <dir> <exitCode> <status> <warnings> <unverifiedJson> <countsRows> <discrepancyRows> [studiesAnswered]
  mkdir -p "$1"
  local issues='[]'
  [ "$4" -gt 0 ] && issues='[{"severity":"WARNING","code":"COUNT_MISMATCH","message":"phs000001.v4 consent 2: dbGaP 3, DB 2"}]'
  printf '{"jobName":"qa-consent-counts","jobType":"PERMANENT","runId":"r","exitCode":"%s","status":"%s","durationMillis":1,"inputValidation":{"issues":[],"counts":{"error":0,"warning":0,"info":0}},"outputValidation":{"issues":%s,"counts":{"error":0,"warning":%s,"info":0}},"metrics":{"studiesChecked":1,"studiesVerified":1,"consentsCompared":2,"consentsMatching":1,"discrepancies":%s,"discrepanciesByType":{},"studiesAnswered":%s,"unverifiedStudies":%s,"apiAttempts":1,"apiRetries":0},"errorMessage":%s}\n' \
    "$2" "$3" "$issues" "$4" "$4" "${8:-1}" "$5" "$([ "$3" = SUCCESS ] && echo null || echo '"dbGaP down"')" > "$1/qa-consent-counts-r.json"
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
UNVERIFIED='phs000002,v1,1,G1,,6,,,UNVERIFIED,dbGaP gave no usable answer: HTTP 503\n'
qa_run "$TMP/partial" SUCCESS_WITH_WARNINGS SUCCESS 1 '["phs000002"]' "$MATCH$UNVERIFIED" "$UNVERIFIED" 1
expect_rc "dbGaP not answering for some studies is a warning" 10 "$TMP/partial"
grep -q 'not for: phs000002' "$TMP/v.log" && pass "names the unverified study" || fail "unverified study not named"
qa_run "$TMP/down" INFRASTRUCTURE_ERROR FAILED 0 '["phs000002"]' "$UNVERIFIED" "$UNVERIFIED" 0
expect_rc "dbGaP answering for no study fails" 1 "$TMP/down"
grep -q 'FAIL.*answered for at least one study (0)' "$TMP/v.log" && pass "says no study was answered" \
  || { fail "no-answer failure not named"; cat "$TMP/v.log"; }
qa_run "$TMP/disagree" SUCCESS_WITH_WARNINGS SUCCESS 1 '[]' "$MATCH$MISMATCH" ''
expect_rc "a discrepancy file missing a non-MATCH row fails" 1 "$TMP/disagree"
mkdir -p "$TMP/none"
expect_rc "a missing report fails" 1 "$TMP/none"
"$RUNNER/validate.sh" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo "preflight.sh"
preflight_rc() {   # <label> <want> <managed inputs> <studies>
  # A closed local port: the dbGaP probe fails at once (a soft warning) and no network is used.
  TF_VAR_managed_inputs_uri="$3" TF_VAR_studies="$4" TF_VAR_dbgap_base_url=http://127.0.0.1:9/study \
    "$RUNNER/preflight.sh" >"$TMP/p.log" 2>&1
  local rc=$?
  [ "$rc" -eq "$2" ] && pass "$1 (exit $rc)" || { fail "$1: exit $rc, want $2"; cat "$TMP/p.log"; }
}
printf 'x\n' > "$TMP/managed.csv"
preflight_rc "a managed inputs file is enough" 10 "$TMP/managed.csv" ""
preflight_rc "a list of study versions is enough" 10 "" "phs000956.v6,PHS001412.v4.p2"
preflight_rc "neither fails" 1 "" ""
preflight_rc "both fail" 1 "$TMP/managed.csv" "phs000956.v6"
preflight_rc "a missing managed inputs file fails" 1 "$TMP/absent.csv" ""
preflight_rc "an entry that is not a study version fails" 1 "" "phs000956.v6,phs000957"
grep -q 'not: phs000957' "$TMP/p.log" && pass "names the bad entry" || fail "bad entry not named"

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
