#!/bin/bash
# Tests for the qa-pipeline-counts runner's scripts. No AWS, Jenkins or JVM needed.
#
#   1. collect-run-counts.py gathers each pipeline's per-consent counts from its artifacts, skipping
#      failed runs and blank counts, and still writes a header-only file when nothing is there.
#   2. validate.sh exits 0 when every count matches, 10 on discrepancies or studies with no summary
#      (and lists them), and 1 when no study had a summary (exit 4), the report is missing, or the
#      CSVs disagree.
#   3. preflight.sh needs MANAGED_INPUTS and an s3:// DATA_ROOT.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$(cd "$HERE/.." && pwd)"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/qa-pipeline-counts-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FAILED=0

pass() { echo "  [ok]   $*"; }
fail() { echo "  [FAIL] $*"; FAILED=$((FAILED + 1)); }
same() {   # <label> <file> <expected content>
  if [ "$(cat "$2" 2>/dev/null)" = "$3" ]; then pass "$1"; else fail "$1"; echo "--- got:"; cat "$2"; echo "--- want:"; echo "$3"; fi
}

echo "collect-run-counts.py"
report() {   # <path> <status> <metrics json>
  mkdir -p "$(dirname "$1")"
  printf '{"jobName":"x","status":"%s","metrics":%s}\n' "$2" "$3" > "$1"
}
A="$TMP/permanent"
report "$A/sstr/phs000001/etl-runners/sstr-populate-rds-participants/reports/sstr-populate-rds-participants-t.json" \
  SUCCESS '{"countsByConsentGroup":{"1":7,"2":3}}'
report "$A/sstr/phs000002/etl-runners/sstr-populate-rds-participants/reports/sstr-populate-rds-participants-t.json" \
  FAILED '{"countsByConsentGroup":{"1":9}}'
report "$A/per-study-all-concepts/phs000001/etl-runners/all-concepts-data-generator/reports/all-concepts-data-generator-t.json" \
  SUCCESS_WITH_WARNINGS '{"participantsPerConsent":{"c1":7,"c2":2}}'
mkdir -p "$A/pipeline-report"
printf 'study_id,abv,consent_code,participants_loaded,participants_written\nphs000001,ONE,1,7,7\nphs000001,ONE,2,3,\n' \
  > "$A/pipeline-report/pipeline_consent_counts.csv"
python3 "$RUNNER/collect-run-counts.py" permanent "$A" "$TMP/permanent.csv" >"$TMP/c.log" 2>&1
same "permanent: SSTR loads, the generator and the pipeline report; a failed load and blank counts skipped" \
  "$TMP/permanent.csv" "study_id,consent_code,source,participants
phs000001,1,sstr-load,7
phs000001,2,sstr-load,3
phs000001,c1,all-concepts-data-generator,7
phs000001,c2,all-concepts-data-generator,2
phs000001,1,pipeline-report:loaded,7
phs000001,1,pipeline-report:written,7
phs000001,2,pipeline-report:loaded,3"

M="$TMP/migration"
mkdir -p "$M/participants-migration" "$M/migration-report"
printf 'study_id,abv,outcome,source,consent_code,participants_loaded\nphs000001,ONE,success,multi,1,402\nphs000002,TWO,failed,none,1,\n' \
  > "$M/participants-migration/participants_migration_consent_counts.csv"
cat > "$M/split-allconcepts-report-tag.json" <<'JSON'
{"studies":[{"studyId":"phs000001","outcome":"SUCCESS","job":{"metrics":{"participantsPerConsent":{"1":400}}}},
            {"studyId":"phs000003","outcome":"FAILED","job":{"metrics":{"participantsPerConsent":{"1":5}}}}]}
JSON
printf 'study_id,abv,consent_code,partition,participants_loaded,participants_split,split_rows,in_manifest,manifest_version_id,study_status\nphs000001,ONE,1,phs000001.c1,402,400,9,true,v,MIGRATED\n' \
  > "$M/migration-report/migration_consent_counts.csv"
python3 "$RUNNER/collect-run-counts.py" migration "$M" "$TMP/migration.csv" >"$TMP/c.log" 2>&1
same "migration: the participants load, the split and the migration report; a failed split skipped" \
  "$TMP/migration.csv" "study_id,consent_code,source,participants
phs000001,1,participants-migration,402
phs000001,1,split-allconcepts,400
phs000001,1,migration-report:loaded,402
phs000001,1,migration-report:split,400"
grep -q 'migration-report:split: 1 study' "$TMP/c.log" && pass "summarizes what it found" || { fail "no summary"; cat "$TMP/c.log"; }

python3 "$RUNNER/collect-run-counts.py" permanent "$TMP/nothing" "$TMP/empty.csv" >/dev/null 2>&1
same "no artifacts: a header-only file" "$TMP/empty.csv" "study_id,consent_code,source,participants"
python3 "$RUNNER/collect-run-counts.py" nightly "$A" "$TMP/x.csv" >/dev/null 2>&1
[ $? -eq 64 ] && pass "an unknown pipeline exits 64" || fail "an unknown pipeline did not exit 64"

echo "validate.sh"
HEADER="study_id,version,in_run,consent_code,consent_short_name,measure,compared_to,dbgap_count,count,status,note"
qa_run() {   # <dir> <exitCode> <status> <warnings> <unverifiedJson> <countsRows> <discrepancyRows>
  mkdir -p "$1"
  local issues='[]'
  [ "$4" -gt 0 ] && issues='[{"severity":"WARNING","code":"COUNT_MISMATCH","message":"phs000001.v4: 1 count(s): c2 db:participants 2 vs dbGaP 3"}]'
  printf '{"jobName":"qa-pipeline-counts","jobType":"PERMANENT","runId":"r","exitCode":"%s","status":"%s","durationMillis":1,"inputValidation":{"issues":[],"counts":{"error":0,"warning":0,"info":0}},"outputValidation":{"issues":%s,"counts":{"error":0,"warning":%s,"info":0}},"metrics":{"pipeline":"permanent","studiesChecked":1,"studiesInRun":1,"studiesVerified":1,"countsCompared":2,"countsMatching":1,"discrepancies":%s,"discrepanciesByType":{},"unverifiedStudies":%s,"summariesFromCache":1,"summariesFromApi":0,"summariesCached":0,"summaryCacheFailures":{},"apiAttempts":0,"apiRetries":0,"runCounts":null,"runCountSources":[]},"errorMessage":%s}\n' \
    "$2" "$3" "$issues" "$4" "$4" "$5" "$([ "$3" = SUCCESS ] && echo null || echo '"no summaries"')" > "$1/qa-pipeline-counts-r.json"
  printf '%s\n%b' "$HEADER" "$6" > "$1/qa_pipeline_counts.csv"
  printf '%s\n%b' "$HEADER" "$7" > "$1/qa_pipeline_discrepancies.csv"
  printf 'study_id\n' > "$1/qa_pipeline_studies.csv"
  printf '# report\n' > "$1/qa-pipeline-report.md"
}
expect_rc() {   # <label> <want> <dir>
  "$RUNNER/validate.sh" "$3" r >"$TMP/v.log" 2>&1
  local rc=$?
  [ "$rc" -eq "$2" ] && pass "$1 (exit $rc)" || { fail "$1: exit $rc, want $2"; cat "$TMP/v.log"; }
}
MATCH='phs000001,v4,true,1,G1,db:participants,subject_count,402,402,MATCH,\n'
BYDESIGN='phs000001,v4,true,0,G0,db:participants,subject_count,576,0,NOT_LOADED_BY_DESIGN,consent 0 (not consented) is not loaded\n'
MISMATCH='phs000001,v4,true,2,G2,db:participants,subject_count,3,2,COUNT_MISMATCH,"a note, with a comma"\n'
qa_run "$TMP/ok" SUCCESS SUCCESS 0 '[]' "$MATCH$BYDESIGN" ''
expect_rc "every count matching passes (consent 0 not loaded is not a finding)" 0 "$TMP/ok"
qa_run "$TMP/warn" SUCCESS_WITH_WARNINGS SUCCESS 1 '[]' "$MATCH$MISMATCH" "$MISMATCH"
expect_rc "discrepancies are warnings" 10 "$TMP/warn"
grep -q 'phs000001 v4 c2 G2, db:participants: 2 vs 3 COUNT_MISMATCH -- a note, with a comma' "$TMP/v.log" \
  && pass "lists each discrepancy" || { fail "discrepancy not listed"; cat "$TMP/v.log"; }
UNVERIFIED='phs000002,v1,false,1,G1,db:participants,subject_count,,6,UNVERIFIED,no cached summary\n'
qa_run "$TMP/partial" SUCCESS_WITH_WARNINGS SUCCESS 1 '["phs000002"]' "$MATCH$UNVERIFIED" "$UNVERIFIED"
expect_rc "a study with no summary is a warning" 10 "$TMP/partial"
grep -q 'not: phs000002 -- a later run fetches them' "$TMP/v.log" && pass "names the unverified study" \
  || { fail "unverified study not named"; cat "$TMP/v.log"; }
qa_run "$TMP/down" INFRASTRUCTURE_ERROR FAILED 0 '["phs000002"]' "$UNVERIFIED" "$UNVERIFIED"
expect_rc "no study with a summary fails" 1 "$TMP/down"
qa_run "$TMP/disagree" SUCCESS_WITH_WARNINGS SUCCESS 1 '[]' "$MATCH$MISMATCH" ''
expect_rc "a discrepancy file missing a row needing follow-up fails" 1 "$TMP/disagree"
mkdir -p "$TMP/none"
expect_rc "a missing report fails" 1 "$TMP/none"
"$RUNNER/validate.sh" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo "preflight.sh"
preflight_rc() {   # <label> <want> <managed inputs> <data root> [pipeline] [fetch missing summaries]
  # A closed local port: the dbGaP probe fails at once (a soft warning) and no network is used.
  TF_VAR_managed_inputs_uri="$3" TF_VAR_data_root_uri="$4" TF_VAR_pipeline="${5:-}" \
    TF_VAR_fetch_missing_summaries="${6:-}" \
    TF_VAR_dbgap_base_url=http://127.0.0.1:9/study "$RUNNER/preflight.sh" >"$TMP/p.log" 2>&1
  local rc=$?
  [ "$rc" -eq "$2" ] && pass "$1 (exit $rc)" || { fail "$1: exit $rc, want $2"; cat "$TMP/p.log"; }
}
printf 'x\n' > "$TMP/managed.csv"
preflight_rc "managed inputs and an s3:// data root pass (dbGaP not asked: fetching is off by default)" 0 "$TMP/managed.csv" s3://b/root permanent
preflight_rc "fetching missing summaries, dbGaP unreachable is a warning" 10 "$TMP/managed.csv" s3://b/root migration true
preflight_rc "fetch missing summaries must be true or false" 1 "$TMP/managed.csv" s3://b/root permanent maybe
preflight_rc "no managed inputs fails" 1 "" s3://b/root
preflight_rc "a missing managed inputs file fails" 1 "$TMP/absent.csv" s3://b/root
preflight_rc "no data root fails" 1 "$TMP/managed.csv" ""
preflight_rc "a local data root fails" 1 "$TMP/managed.csv" /tmp/root
preflight_rc "an unknown pipeline fails" 1 "$TMP/managed.csv" s3://b/root nightly

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
