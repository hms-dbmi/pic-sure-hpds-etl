#!/bin/bash
#
# Shell tests for preflight.sh and validate.sh. `aws` is replaced by a stub on PATH, so no AWS
# access or credentials are needed. Run: ./test/run-tests.sh   (or: make test)
#
# The stub is driven by environment variables:
#   STUB_MISSING      substring; head-object on a key containing it fails (object does not exist)
#   STUB_SIZE_BYTES   ContentLength returned by head-object (default 1073741824 = 1 GiB)
#   STUB_PREFIX_COUNT objects reported under a listed prefix (default 0)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$(cd "$HERE/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"
cat > "$WORK/bin/aws" <<'STUB'
#!/bin/bash
# Test stub for the two calls preflight.sh makes. Anything else is a test bug.
case "$1 $2" in
  "s3api head-object")
    key=""
    while [[ $# -gt 0 ]]; do
      [[ "$1" == "--key" ]] && key="$2"
      shift
    done
    if [[ -n "${STUB_MISSING:-}" && "$key" == *"$STUB_MISSING"* ]]; then
      echo "An error occurred (404) when calling the HeadObject operation: Not Found" >&2
      exit 254
    fi
    echo "${STUB_SIZE_BYTES:-1073741824}"
    ;;
  "s3api list-objects-v2")
    echo "${STUB_PREFIX_COUNT:-0}"
    ;;
  *)
    echo "aws stub: unexpected call: $*" >&2
    exit 99
    ;;
esac
STUB
chmod +x "$WORK/bin/aws"
export PATH="$WORK/bin:$PATH"

PASSED=0
FAILED=0

# expect <description> <expected exit code> <command...>
expect() {
  local desc="$1" want="$2"
  shift 2
  local out rc
  out="$("$@" 2>&1)"
  rc=$?
  if [[ "$rc" == "$want" ]]; then
    PASSED=$((PASSED + 1))
    echo "ok   - $desc"
  else
    FAILED=$((FAILED + 1))
    echo "FAIL - $desc (exit $rc, wanted $want)"
    printf '%s\n' "$out" | sed 's/^/         | /'
  fi
}

# Runs preflight.sh with a clean, valid baseline overridden by the VAR=value arguments.
preflight() {
  (
    unset STUB_MISSING STUB_SIZE_BYTES STUB_PREFIX_COUNT TF_VAR_fence_mapping TF_VAR_force TF_VAR_globals TF_VAR_depth TF_VAR_root_volume_size
    export TF_VAR_input_uri="s3://bucket-a/data/transforms/all_concepts_merged/allConcepts_data_analyzer.csv"
    export TF_VAR_output_uri="s3://bucket-a/data/transforms/all_concepts_split/run-1/"
    for kv in "$@"; do export "${kv?}"; done
    cd "$RUNNER" && ./preflight.sh
  )
}

echo "== preflight.sh =="
expect "clean run passes"                                   0  preflight
expect "missing input fails"                                1  preflight STUB_MISSING=allConcepts_data_analyzer
expect "output under the legacy bucket prefix fails"        1  preflight TF_VAR_output_uri=s3://bucket-a/avillach-73-bdcatalyst-etl/out/
expect "output in the legacy bucket fails"                  1  preflight TF_VAR_output_uri=s3://avillach-73-bdcatalyst-etl/out/
expect "output under the mirror fails even with force"      1  preflight TF_VAR_output_uri=s3://bucket-a/avillach-73-bdcatalyst-etl/out/ TF_VAR_force=true
expect "non-empty output prefix fails"                      1  preflight STUB_PREFIX_COUNT=1
expect "non-empty output prefix passes with force"          0  preflight STUB_PREFIX_COUNT=1 TF_VAR_force=true
expect "local output path fails"                            1  preflight TF_VAR_output_uri=/tmp/out
expect "negative depth fails"                               1  preflight TF_VAR_depth=-1
expect "unknown globals mode fails"                         1  preflight TF_VAR_globals=both
expect "disk below 2x the input fails"                      1  preflight STUB_SIZE_BYTES=$((600 * 1073741824)) TF_VAR_root_volume_size=1000
expect "disk of exactly 2x input + headroom passes"         0  preflight STUB_SIZE_BYTES=$((400 * 1073741824)) TF_VAR_root_volume_size=830
expect "by-patient on a 2x disk warns (exit 10)"            10 preflight STUB_SIZE_BYTES=$((400 * 1073741824)) TF_VAR_root_volume_size=830 TF_VAR_globals=by-patient
expect "by-patient on a 4x disk passes"                     0  preflight STUB_SIZE_BYTES=$((400 * 1073741824)) TF_VAR_root_volume_size=1700 TF_VAR_globals=by-patient
expect "unreadable fence mapping warns (exit 10)"           10 preflight TF_VAR_fence_mapping=s3://bucket-a/gone/fence.json STUB_MISSING=gone/fence.json
expect "fence mapping none skips the check"                 0  preflight TF_VAR_fence_mapping=none

# --- validate.sh ---------------------------------------------------------------------------
report() { # report <dir> <run id> <jq-overrides-as-json>
  local dir="$1" run="$2" overrides="${3:-}"
  [[ -n "$overrides" ]] || overrides='{}'
  mkdir -p "$dir"
  jq -n --arg run "$run" --argjson over "$overrides" '
    {
      jobName: "split-allconcepts-by-consent", jobType: "PERMANENT", runId: $run,
      exitCode: "SUCCESS", status: "SUCCESS", durationMillis: 1000, errorMessage: null,
      inputValidation:  { phase: "input",  issues: [], counts: { error: 0, warning: 0, info: 0 } },
      outputValidation: { phase: "output", issues: [], counts: { error: 0, warning: 0, info: 0 } },
      metrics: {
        outputUri: "s3://bucket-a/data/transforms/all_concepts_split/run-1/", globals: "own",
        totalRows: 100, studyRows: 60, globalRows: 40, droppedStudyNoConsent: 0, droppedGlobalNoConsent: 0,
        rejectedRows: 0, partitionLines: 105, balanced: true, partitions: 5, studyPartitions: 3, globalPartitions: 2,
        timestampLikelySeconds: 0, manifestUri: "s3://bucket-a/data/transforms/all_concepts_split/run-1/split_manifest.json"
      }
    } * $over' > "$dir/split-allconcepts-by-consent-$run.json"
}

validate() { (cd "$RUNNER" && ./validate.sh "$@"); }

echo "== validate.sh =="
report "$WORK/ok" r1
expect "balanced report passes"                             0  validate "$WORK/ok" r1
expect "missing report fails"                               1  validate "$WORK/ok" nope
expect "usage error is 64"                                  64 validate

report "$WORK/unbal" r2 '{"metrics":{"totalRows":101}}'
expect "rows that do not add up fail"                       1  validate "$WORK/unbal" r2

report "$WORK/flag" r3 '{"metrics":{"balanced":false}}'
expect "job-reported imbalance fails"                       1  validate "$WORK/flag" r3

report "$WORK/empty" r4 '{"metrics":{"totalRows":0,"studyRows":0,"globalRows":0,"partitions":0}}'
expect "an empty split fails"                               1  validate "$WORK/empty" r4

report "$WORK/dropped" r5 '{"metrics":{"studyRows":50,"droppedStudyNoConsent":10}}'
expect "dropped rows are a warning (exit 10)"               10 validate "$WORK/dropped" r5

# 100 input rows = 55 study + 40 global + 5 quarantined: balances, but the quarantine is a warning
report "$WORK/rejected" r9 '{"metrics":{"studyRows":55,"rejectedRows":5}}'
expect "quarantined rows that balance are a warning (exit 10)" 10 validate "$WORK/rejected" r9
expect "the quarantine warning is printed"                  0  bash -c "cd '$RUNNER' && ./validate.sh '$WORK/rejected' r9 | grep -q 'no rows quarantined'"

# rejected rows not accounted for in the totals still fail the sum check
report "$WORK/rejunbal" r10 '{"metrics":{"rejectedRows":5}}'
expect "rejected rows that do not add up fail"              1  validate "$WORK/rejunbal" r10

report "$WORK/secs" r6 '{"metrics":{"timestampLikelySeconds":3}}'
expect "likely-seconds timestamps are a warning (exit 10)"  10 validate "$WORK/secs" r6

report "$WORK/nomanifest" r7 '{"metrics":{"manifestUri":null}}'
expect "a missing manifest fails"                           1  validate "$WORK/nomanifest" r7

report "$WORK/failed" r8 '{"exitCode":"VALIDATION_FAILED","status":"FAILED"}'
expect "a failed job report fails"                          1  validate "$WORK/failed" r8

echo ""
echo "$PASSED passed, $FAILED failed"
[[ "$FAILED" -eq 0 ]]
