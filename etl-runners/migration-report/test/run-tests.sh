#!/bin/bash
# Tests for migration-report.py: the report is written from a minimal migration's artifacts, and
# from none at all. No AWS, Jenkins or JVM needed.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REPORT="$ROOT/etl-runners/migration-report/migration-report.py"
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
echo "migration-report.py"

base "$TMP/base"
MD="$TMP/base-out/migration-report.md"
if run base && grep -q '^| Study status | Studies |' "$MD" \
   && jq -e '.reportVersion == 1 and .generatedAt == "2026-10-16T00:00:00Z" and (has("consentCountQa") | not)' \
        "$TMP/base-out/migration-report.json" >/dev/null \
   && grep -q '^phs000001,ONE,1,' "$TMP/base-out/migration_consent_counts.csv" \
   && [ -f "$TMP/base-out/migration_study_outcomes.csv" ] && [ -f "$TMP/base-out/migration_review.csv" ]; then
  pass "a migration's report is written: markdown, JSON and CSVs"
else
  fail "base report: $(cat "$TMP/base.log"; head -20 "$MD")"
fi

mkdir -p "$TMP/empty"
run empty && grep -q '^| Study status | Studies |' "$TMP/empty-out/migration-report.md" \
  && pass "an empty artifacts dir still yields a report" || fail "empty artifacts: $(cat "$TMP/empty.log")"
python3 "$REPORT" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
