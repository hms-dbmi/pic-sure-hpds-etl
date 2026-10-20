#!/bin/bash
# Tests for migration-report.py's consent count QA section (and that the report is still written
# around it). No AWS, Jenkins or JVM needed. The QA fixtures are the permanent pipeline report's
# (etl-runners/pipeline-report/test/fixture-qa), which the same code (qa_report.py) renders.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REPORT="$ROOT/etl-runners/migration-report/migration-report.py"
QA_FIXTURE="$ROOT/etl-runners/pipeline-report/test/fixture-qa/qa-consent-counts"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/migration-report-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FAILED=0

pass() { echo "  [ok]   $*"; }
fail() { echo "  [FAIL] $*"; FAILED=$((FAILED + 1)); }

# A minimal migration: participants-migration loaded two studies.
base() {   # <dir>
  mkdir -p "$1/participants-migration"
  cat > "$1/participants-migration/participants-migration-t.json" <<'JSON'
{"jobName":"participants-migration","runId":"t","exitCode":"SUCCESS","status":"SUCCESS","inputValidation":{"issues":[],"counts":{"error":0,"warning":0}},"outputValidation":{"issues":[],"counts":{"error":0,"warning":0}},"metrics":{},"errorMessage":null}
JSON
  printf 'study_id,abv,outcome,source,sstr_file,sstr_status,subject_multi_file,sample_multi_file,data_dict_file,multi_status,consent_column,mapping_rows,unresolved_mapping_rows,error\nphs000001,ONE,success,multi,,,s.txt,,,ok,CONSENT,10,0,\nphs000002,TWO,success,sstr,sstr.txt,ok,,,,,,10,0,\n' \
    > "$1/participants-migration/participants_migration_sources.csv"
  printf 'study_id,abv,outcome,source,consent_code,participants_loaded\nphs000001,ONE,success,multi,1,402\nphs000002,TWO,success,sstr,1,10\n' \
    > "$1/participants-migration/participants_migration_consent_counts.csv"
}
run() {   # <name> -- reports $TMP/<name> into $TMP/<name>-out
  MIGRATION_REPORT_NOW=2026-10-16T00:00:00Z python3 "$REPORT" "$TMP/$1" t "$TMP/$1-out" UNSTABLE >"$TMP/$1.log" 2>&1
}
qa_first() {
  awk '/^## Consent count QA/ { qa = NR } /^\| Study status/ { st = NR } END { exit !(qa && st && qa < st) }' "$1"
}
qa_report_json() { find "$TMP/$1/qa-consent-counts" -name 'qa-consent-counts-*.json'; }

echo "migration-report.py: consent count QA"

base "$TMP/none"
if run none && grep -q '^\*\*QA did not run\*\*' "$TMP/none-out/migration-report.md" && qa_first "$TMP/none-out/migration-report.md" \
   && jq -e '.consentCountQa.outcome == "NOT_RUN" and (.stages[] | select(.stage == "qa-consent-counts") | .ran == false)' \
        "$TMP/none-out/migration-report.json" >/dev/null; then
  pass "QA that never ran is reported as not run, at the top"
else
  fail "QA not run: $(cat "$TMP/none.log")"
fi

base "$TMP/disc"; cp -R "$QA_FIXTURE" "$TMP/disc/"
MD="$TMP/disc-out/migration-report.md"
if run disc && qa_first "$MD" \
   && grep -q '^\*\*2 discrepancy(ies)\*\* in 2 study/studies (COUNT_MISMATCH 1, MISSING_IN_DB 1)' "$MD" \
   && grep -q '^| phs000001 | v1 | 0 | NRUP | 576 | 0 |  | MISSING_IN_DB |  |$' "$MD" \
   && jq -e '.consentCountQa.outcome == "DISCREPANCIES" and .summary.consentCountDiscrepancies == 2
             and (.stages[] | select(.stage == "qa-consent-counts") | .ran and .warnings == 2)' \
        "$TMP/disc-out/migration-report.json" >/dev/null \
   && [ -f "$TMP/disc-out/qa_consent_count_discrepancies.csv" ] && [ -f "$TMP/disc-out/qa_consent_counts.csv" ] \
   && grep -q 'consent count QA: 2 discrepancy(ies)' "$TMP/disc.log" \
   && [ -f "$TMP/disc-out/migration_consent_counts.csv" ]; then
  pass "QA discrepancies are listed at the top, in the JSON and as CSV copies; the rest of the report is still written"
else
  fail "QA discrepancies: $(cat "$TMP/disc.log"; sed -n '/^## Consent count QA/,/^| Study status/p' "$MD")"
fi

base "$TMP/match"; cp -R "$QA_FIXTURE" "$TMP/match/"
F=$(qa_report_json match)
jq '.exitCode = "SUCCESS" | .metrics.discrepancies = 0 | .metrics.discrepanciesByType = {} | .metrics.studiesWithDiscrepancies = []' \
  "$F" > "$F.tmp" && mv "$F.tmp" "$F"
head -1 "$(dirname "$F")/qa_consent_count_discrepancies.csv" > "$TMP/h" && mv "$TMP/h" "$(dirname "$F")/qa_consent_count_discrepancies.csv"
if run match && grep -q "^All 5 consent group(s) match dbGaP and this run's load report" "$TMP/match-out/migration-report.md" \
   && jq -e '.consentCountQa.outcome == "ALL_MATCH"' "$TMP/match-out/migration-report.json" >/dev/null; then
  pass "QA with every consent matching says so"
else
  fail "QA all match: $(sed -n '/^## Consent count QA/,/^| Study status/p' "$TMP/match-out/migration-report.md")"
fi

base "$TMP/failed"; cp -R "$QA_FIXTURE" "$TMP/failed/"
F=$(qa_report_json failed)
jq '.exitCode = "INFRASTRUCTURE_ERROR" | .status = "FAILED" | .metrics.unverifiedStudies = ["phs000003"] | .errorMessage = "dbGaP down."' \
  "$F" > "$F.tmp" && mv "$F.tmp" "$F"
if run failed && grep -q '^\*\*QA failed: INFRASTRUCTURE_ERROR\*\*: dbGaP down. Unverified: phs000003.$' "$TMP/failed-out/migration-report.md" \
   && grep -q '^| phs000002 | v3 | 1 | GRU |' "$TMP/failed-out/migration-report.md" \
   && jq -e '.consentCountQa.outcome == "FAILED"' "$TMP/failed-out/migration-report.json" >/dev/null; then
  pass "a failed QA (dbGaP down) says so at the top and still lists what it compared"
else
  fail "QA failed: $(sed -n '/^## Consent count QA/,/^| Study status/p' "$TMP/failed-out/migration-report.md")"
fi

mkdir -p "$TMP/empty"
run empty && grep -q '^\*\*QA did not run\*\*' "$TMP/empty-out/migration-report.md" \
  && pass "an empty artifacts dir still yields a report" || fail "empty artifacts: $(cat "$TMP/empty.log")"
python3 "$REPORT" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
