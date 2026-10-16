#!/bin/bash
# Tests for the permanent orchestrator's shell/python helpers. No AWS, Jenkins or runtime needed.
#
#   1. pipeline-report.py on test/fixture/downstream-artifacts must reproduce test/expected/
#      exactly (regenerate with UPDATE=1 after an intended change, and review the diff).
#   2. etl-runners/common/probe-study-inputs.sh against a stubbed `aws` must find, per study, the
#      SSTR candidates, decoded CSV count/bytes and the mapping -- and report a listing error.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/pipeline-report-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FAILED=0

pass() { echo "  [ok]   $*"; }
fail() { echo "  [FAIL] $*"; FAILED=$((FAILED + 1)); }

echo "pipeline-report.py"
if PIPELINE_REPORT_NOW=2026-10-16T00:00:00Z python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" \
     "$HERE/fixture/downstream-artifacts" jenkins-test-1 "$TMP/report" FAILURE >"$TMP/report.log" 2>&1; then
  pass "report written"
else
  fail "report exited $? ($(cat "$TMP/report.log"))"
fi
if [ "${UPDATE:-}" = 1 ]; then
  cp "$TMP"/report/pipeline* "$HERE/expected/"
  echo "  expected/ updated"
fi
for f in pipeline-report.json pipeline-report.md pipeline_study_outcomes.csv; do
  if diff -u "$HERE/expected/$f" "$TMP/report/$f" >"$TMP/$f.diff"; then
    pass "$f matches expected"
  else
    fail "$f differs from expected:"; cat "$TMP/$f.diff"
  fi
done
if python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/empty" run "$TMP/empty-out" FAILURE >/dev/null 2>&1 \
   && grep -q 'stopped before it resolved its studies' "$TMP/empty-out/pipeline-report.md"; then
  pass "an empty artifacts dir still yields a report"
else
  fail "no report for an empty artifacts dir"
fi
# Variants of the fixture: the merge and manifest succeeded (a study can then be COMPLETE), or the
# merge failed without naming a study (every loaded study is then FAILED, and the report is still
# written even though that stage's exitCode is not a string).
report_row() { awk -F, -v s="$2" '$1 == s { print $3 }' "$1/pipeline_study_outcomes.csv"; }
stage_report() {   # <artifacts> <dir> <job> <exitCode> <status>
  mkdir -p "$1/$2"
  printf '{"jobName":"%s","runId":"t","exitCode":"%s","status":"%s","inputValidation":{"issues":[],"counts":{"error":0,"warning":0}},"outputValidation":{"issues":[],"counts":{"error":0,"warning":0}},"metrics":{},"errorMessage":null}\n' \
    "$3" "$4" "$5" > "$1/$2/$3-t.json"
}
cp -R "$HERE/fixture/downstream-artifacts" "$TMP/published"
stage_report "$TMP/published" merge-allconcepts merge-allconcepts SUCCESS SUCCESS
stage_report "$TMP/published" allconcepts-manifest generate-allconcepts-manifest SUCCESS SUCCESS
python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/published" run "$TMP/published-out" SUCCESS >/dev/null 2>&1
[ "$(report_row "$TMP/published-out" phs000001)" = COMPLETE ] \
  && pass "a study through the manifest is COMPLETE" || fail "phs000001: $(report_row "$TMP/published-out" phs000001)"
[ "$(report_row "$TMP/report" phs000001)" = NOT_REACHED ] \
  && pass "a study whose merge/manifest never ran is NOT_REACHED" || fail "phs000001 without a manifest: $(report_row "$TMP/report" phs000001)"
cp -R "$HERE/fixture/downstream-artifacts" "$TMP/mergefail"
mkdir -p "$TMP/mergefail/merge-allconcepts"
printf '{"jobName":"merge-allconcepts","runId":"t","exitCode":4,"status":"FAILED","metrics":{},"errorMessage":null}\n' \
  > "$TMP/mergefail/merge-allconcepts/merge-allconcepts-t.json"
if python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/mergefail" run "$TMP/mergefail-out" FAILURE >/dev/null 2>&1 \
   && [ "$(report_row "$TMP/mergefail-out" phs000001)" = FAILED ]; then
  pass "a run-wide failure naming no study marks the loaded studies FAILED"
else
  fail "merge failure: rc/status $(report_row "$TMP/mergefail-out" phs000001)"
fi
# A failed merge's -folders.json (written after, so newer) is not taken for its report, and its
# FAILED folders become review items for their studies.
mkdir -p "$TMP/mergefail/merge-allconcepts/sub"
printf '{"runId":"t","foldersFailed":1,"consentFoldersDiscovered":2,"actions":{"phs000002/allConcepts/c1":{"action":"FAILED","sourceFileCount":1,"error":"S3 read timed out"},"phs000001/allConcepts/c1":{"action":"UP_TO_DATE","sourceFileCount":1}}}\n' \
  > "$TMP/mergefail/merge-allconcepts/sub/merge-allconcepts-t-folders.json"
touch -t 203001010000 "$TMP/mergefail/merge-allconcepts/sub/merge-allconcepts-t-folders.json"
python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/mergefail" run "$TMP/mergefail-out2" FAILURE >/dev/null 2>&1
if jq -e '(.stages[] | select(.stage == "merge-allconcepts") | .exitCode == 4 and (.foldersReport | endswith("-folders.json")))
          and ([.review[] | select(.code == "MERGE_FOLDER_FAILED")] | length == 1 and .[0].studyId == "phs000002"
               and (.[0].detail | contains("S3 read timed out")))' "$TMP/mergefail-out2/pipeline-report.json" >/dev/null; then
  pass "a failed merge's -folders.json names its failed folders, and is not read as the merge report"
else
  fail "merge -folders.json: $(jq -c '[.stages[] | select(.stage == "merge-allconcepts")], [.review[] | select(.stage == "merge-allconcepts")]' "$TMP/mergefail-out2/pipeline-report.json")"
fi
# An older run's -folders.json (another run id) is ignored.
mkdir -p "$TMP/mergefail/merge-allconcepts/old"
printf '{"runId":"old","actions":{"phs000001/allConcepts/c1":{"action":"FAILED","error":"old failure"}}}\n' \
  > "$TMP/mergefail/merge-allconcepts/old/merge-allconcepts-old-folders.json"
touch -t 203101010000 "$TMP/mergefail/merge-allconcepts/old/merge-allconcepts-old-folders.json"
python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/mergefail" run "$TMP/mergefail-out3" FAILURE >/dev/null 2>&1
if jq -e '[.review[] | select(.code == "MERGE_FOLDER_FAILED")] | length == 1 and .[0].studyId == "phs000002"' \
     "$TMP/mergefail-out3/pipeline-report.json" >/dev/null; then
  pass "only the merge report's own run's -folders.json is read"
else
  fail "stale -folders.json: $(jq -c '[.review[] | select(.code == "MERGE_FOLDER_FAILED")]' "$TMP/mergefail-out3/pipeline-report.json")"
fi
# VCF consent groups held back for bad data are listed with the files deleted for them.
cp -R "$HERE/fixture/downstream-artifacts" "$TMP/heldback"
VCF=$(find "$TMP/heldback/vcf-indexes" -name 'create-vcf-indexes-*.json')
jq '.exitCode = "VALIDATION_FAILED" | .status = "FAILED"
    | .outputValidation.issues += [{"severity":"ERROR","code":"ID_COUNT_MISMATCH","message":"phs000001.c1 held back (not written; its previously published files were deleted: s3://b/v/phs000001.c1_vcfIndex.tsv): bad ids","location":"phs000001.c1","context":{"studyId":"phs000001","consent":"1"}}]
    | .metrics.heldBackFilesDeleted = 1
    | .metrics.heldBackGroups = [{"consentGroup":"phs000001.c1","studyId":"phs000001","consent":"1","reasons":["ID_COUNT_MISMATCH"],"deletedFiles":["s3://b/v/phs000001.c1_vcfIndex.tsv"]},
                                 {"consentGroup":"phs000002.c2","studyId":"phs000002","consent":"2","reasons":["BAD_STUDY_ABV"],"deletedFiles":[]}]' \
  "$VCF" > "$VCF.tmp" && mv "$VCF.tmp" "$VCF"
python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" "$TMP/heldback" run "$TMP/heldback-out" FAILURE >/dev/null 2>&1
if jq -e '.summary.heldBackVcfGroups == 2 and .heldBackVcfGroups[0].deletedFiles == ["s3://b/v/phs000001.c1_vcfIndex.tsv"]' \
     "$TMP/heldback-out/pipeline-report.json" >/dev/null \
   && grep -q '^| phs000001.c1 | phs000001 | ID_COUNT_MISMATCH | s3://b/v/phs000001.c1_vcfIndex.tsv |$' "$TMP/heldback-out/pipeline-report.md" \
   && grep -q '^| phs000002.c2 | phs000002 | BAD_STUDY_ABV | none (nothing was published before) |$' "$TMP/heldback-out/pipeline-report.md" \
   && [ "$(report_row "$TMP/heldback-out" phs000001)" = FAILED ]; then
  pass "held-back VCF consent groups are listed with their deleted files, and fail their study"
else
  fail "held-back VCF groups: $(jq -c '.heldBackVcfGroups' "$TMP/heldback-out/pipeline-report.json"); phs000001 $(report_row "$TMP/heldback-out" phs000001)"
fi
python3 "$ROOT/etl-runners/pipeline-report/pipeline-report.py" >/dev/null 2>&1
[ $? -eq 64 ] && pass "usage error exits 64" || fail "usage error did not exit 64"

echo "probe-study-inputs.sh"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/aws" <<'STUB'
#!/bin/bash
# Stub: `aws s3 ls <uri> [--region r]` answered from canned listings.
uri="$3"
case "$uri" in
  s3://b/r/phs000001/rawData/)
    printf '2026-01-01 00:00:00        100 sstr_phs000001.v1.txt\n2026-01-01 00:00:00        100 BDC-ingestion-only__sstr_phs000001.v1.txt\n                           PRE old/\n2026-01-01 00:00:00         10 notes.md\n' ;;
  s3://b/r/phs000001/decoded_data/)
    printf '2026-01-01 00:00:00       1000 a.csv\n2026-01-01 00:00:00       2000 b with space.CSV\n2026-01-01 00:00:00          5 readme.txt\n' ;;
  s3://b/r/phs000001/mappings/mapping2.csv)
    printf '2026-01-01 00:00:00         50 mapping2.csv\n2026-01-01 00:00:00         50 mapping2.csv.bak\n' ;;
  s3://b/r/phs000002/mappings/mapping2.csv)
    printf '2026-01-01 00:00:00         50 mapping2.csv.bak\n'; exit 0 ;;
  s3://b/r/phs000003/rawData/)
    echo "An error occurred (AccessDenied) when calling the ListObjectsV2 operation: Access Denied" >&2; exit 254 ;;
  *) exit 1 ;;   # an empty prefix: exit 1, nothing on stderr
esac
STUB
chmod +x "$TMP/bin/aws"
printf 'phs000001\nphs000002\nphs000003\n' > "$TMP/studies.txt"
if PATH="$TMP/bin:$PATH" DATA_ROOT=s3://b/r/ DECODED_DATA_DIR=/decoded_data/ CONCEPT_MAPPING_FILE=mappings/mapping2.csv \
   PROBE_PARALLELISM=3 bash "$ROOT/etl-runners/common/probe-study-inputs.sh" "$TMP/studies.txt" "$TMP/probe.tsv" >/dev/null; then
  pass "probe wrote its TSV"
else
  fail "probe exited $?"
fi
expect() {
  local sid="$1" want="$2" got
  got=$(awk -F'\t' -v s="$sid" '$1 == s { print $2 "|" $3 "|" $4 "|" $5 "|" ($6 != "" ? "ERR" : "") }' "$TMP/probe.tsv")
  [ "$got" = "$want" ] && pass "$sid: $want" || fail "$sid: want '$want', got '$got'"
}
expect phs000001 'sstr_phs000001.v1.txt/BDC-ingestion-only__sstr_phs000001.v1.txt|2|3000|1|'
expect phs000002 '|0|0|0|'
expect phs000003 '|0|0|0|ERR'
[ "$(head -1 "$TMP/probe.tsv")" = $'study_id\tsstr_candidates\tcsv_count\tcsv_bytes\tmapping_found\tprobe_error' ] \
  && pass "header" || fail "header: $(head -1 "$TMP/probe.tsv")"
[ "$(cut -f1 "$TMP/probe.tsv" | tail -n +2 | paste -sd, -)" = "phs000001,phs000002,phs000003" ] \
  && pass "rows keep the input order" || fail "rows out of order"

echo
if [ "$FAILED" -gt 0 ]; then
  echo "$FAILED check(s) failed"
  exit 1
fi
echo "all checks passed"
