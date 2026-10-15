#!/bin/bash
#
# Tests for the migration's Split AllConcepts stage scripts:
#
#   plan_*     plan-studies.sh: input checks, sizes, largest-first order, lanes, volume sizing
#   pooled_*   run-pooled-study.sh end to end, through the real ../common/pool-dispatch.sh and
#              ../common/monitor-runner.sh, against stubs/aws, which simulates S3 and a pool
#              worker running each task per a scenario (ok, warn, data, validation, infra, die, hang)
#   record_*   record-study-result.sh for the lanes run-pooled-study.sh does not cover
#   report_*   split-report.sh: the consolidated JSON and markdown, and its exit code
#
# Each area has success and failure cases. Needs bash and jq; no AWS access.
#
# Usage: run-tests.sh [name-filter]
# Exit:  0 all passed | 1 any failed
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPLIT="$(cd "$HERE/.." && pwd)"
COMMON="$(cd "$SPLIT/../common" && pwd)"
FILTER="${1:-}"
PASSED=0
FAILED=0
FAILURES=()

# ---------------------------------------------------------------------------- harness

setup() {
    T=$(mktemp -d)
    export STUB_STATE="$T/state"
    mkdir -p "$STUB_STATE/s3/data/root" "$STUB_STATE/scenario"
    touch "$STUB_STATE/aws.log"
    export PATH="$HERE/stubs:$PATH" AWS_REGION=us-east-1
    # monitor-runner.sh, without its waits
    export POLL_INTERVAL=0 GRACE_TIMEOUT=0 BOOT_TIMEOUT=5 PIPELINE_TIMEOUT=60 RETRY_DELAY=0
    export DISPATCH_CHECK_DELAY=0
    export DATA_ROOT=s3://data/root
    unset LARGE_STUDY_GB RETRY_ON_INFRA POOL_LOG_PREFIX INPUT_BYTES STAGE_STARTED_EPOCH
}

teardown() { rm -rf "$T"; }

# A legacy allConcepts input of <bytes> for <study> under DATA_ROOT.
given_input() {
    local f="$STUB_STATE/s3/data/root/$1/legacy/allConcepts/$1_allConcepts_new_search_with_data_analyzer.csv"
    mkdir -p "$(dirname "$f")"; echo "x" > "$f"; echo "$2" > "$f.size"
}
studies() { printf '%s\n' "$@" > "$T/studies.tsv"; }   # lines: id<TAB>abv<TAB>mapping

run_plan() { RC=0; OUT=$("$SPLIT/plan-studies.sh" "$T/studies.tsv" "$T/plan" 2>&1) || RC=$?; }
planned_rows() { tail -n +2 "$T/plan/plan.tsv"; }

# Runs one study on worker i-0abc1234 with the given per-attempt scenario.
run_pooled() {
    local sid="$1" scenario="$2"
    echo "$scenario" > "$STUB_STATE/scenario/$sid"
    RESULT="$T/out/$sid/study-result.json"
    RC=0
    OUT=$(STUDY_ID="$sid" ABBREVIATION="ABV" INPUT_URI="s3://data/root/$sid/in.csv" MAPPING_URI="s3://data/m.csv" \
          OUTPUT_URI="s3://data/root" RUN_ID="build-1-split-$sid" POOL_INSTANCE=i-0abc1234 \
          POOL_MAILBOX=s3://data/etl-runner/pools/p1 POOL_LOG_PREFIX=s3://data/etl-runner/logs/split-allconcepts-p1 \
          "$SPLIT/run-pooled-study.sh" "$T/out/$sid" 2>&1) || RC=$?
}
result() { jq -r "$1" "$RESULT"; }

fail() { echo "      $*"; return 1; }
expect_rc() { [[ "$RC" == "$1" ]] || fail "exit $RC, expected $1. Output:"$'\n'"$OUT"; }
expect_out() { [[ "$OUT" == *"$1"* ]] || fail "output lacks '$1'. Output:"$'\n'"$OUT"; }
expect_eq() { [[ "$1" == "$2" ]] || fail "got '$1', expected '$2'"; }
expect_file_has() { grep -qF -- "$2" "$1" || fail "$1 lacks '$2':"$'\n'"$(cat "$1" 2>/dev/null)"; }

run_test() {
    local name="$1"
    [[ -n "$FILTER" && "$name" != *"$FILTER"* ]] && return
    setup
    # Not inside `if`: that would switch off set -e, so only a test's last assertion would count.
    ( set -e; "$name" )
    if (( $? == 0 )); then
        echo "  [ok]   $name"; PASSED=$((PASSED + 1))
    else
        echo "  [FAIL] $name"; FAILED=$((FAILED + 1)); FAILURES+=("$name")
    fi
    teardown
}

# ---------------------------------------------------------------------------- plan-studies.sh

plan_orders_largest_first_and_routes_large_inputs_to_their_own_lane() {
    given_input phs000001 1000
    given_input phs000002 $((40 * 1024 * 1024 * 1024))
    given_input phs000003 50000
    studies $'phs000001\tA\ts3://m/1' $'phs000002\tB\ts3://m/2' $'phs000003\tC\ts3://m/3'
    LARGE_STUDY_GB=5 run_plan
    expect_rc 0
    expect_eq "$(planned_rows | cut -f2 | tr '\n' ' ')" "phs000002 phs000003 phs000001 "
    expect_eq "$(planned_rows | cut -f1 | tr '\n' ' ')" "large pool pool "
    # 40 GiB input: twice the input plus 50 GiB
    expect_eq "$(planned_rows | head -1 | cut -f7)" "130"
    expect_eq "$(planned_rows | sed -n 2p | cut -f7)" "-"
    expect_eq "$(planned_rows | head -1 | cut -f5)" \
        "s3://data/root/phs000002/legacy/allConcepts/phs000002_allConcepts_new_search_with_data_analyzer.csv"
    expect_eq "$(planned_rows | head -1 | cut -f6)" "s3://m/2"
    expect_out "2 pool-lane, 1 large-lane"
}

plan_gives_a_large_study_at_least_100_gib() {
    given_input phs000001 $((5 * 1024 * 1024 * 1024))
    studies $'phs000001\tA\ts3://m/1'
    LARGE_STUDY_GB=5 run_plan
    expect_rc 0
    expect_eq "$(planned_rows | cut -f1,7)" $'large\t100'
}

plan_with_threshold_zero_puts_everything_in_the_pool() {
    given_input phs000001 $((40 * 1024 * 1024 * 1024))
    studies $'phs000001\tA\ts3://m/1'
    LARGE_STUDY_GB=0 run_plan
    expect_rc 0
    expect_eq "$(planned_rows | cut -f1)" "pool"
}

plan_lists_missing_and_invalid_studies_without_failing() {
    given_input phs000001 10
    studies $'phs000001\tA\ts3://m/1' $'phs000009\tGONE\ts3://m/9' $'phs12\tBAD\ts3://m/x'
    run_plan
    expect_rc 0
    expect_eq "$(planned_rows | cut -f2)" "phs000001"
    expect_file_has "$T/plan/missing.tsv" $'phs000009\tGONE\ts3://data/root/phs000009/legacy/allConcepts/phs000009_allConcepts_new_search_with_data_analyzer.csv\tlegacy allConcepts input not found'
    expect_file_has "$T/plan/missing.tsv" $'phs12\tBAD\t\tstudy id is not phs###### (6 digits)'
    expect_out "phs000009: legacy allConcepts input not found"
}

plan_fails_when_an_input_cannot_be_checked() {
    given_input phs000001 10
    given_input phs000002 10
    echo phs000002 > "$STUB_STATE/head_error"
    studies $'phs000001\tA\ts3://m/1' $'phs000002\tB\ts3://m/2'
    run_plan
    expect_rc 1
    expect_out "phs000002: could not check"
    expect_out "Forbidden"
    expect_out "not planning a partial stage"
}

plan_rejects_a_non_s3_data_root() {
    studies $'phs000001\tA\ts3://m/1'
    DATA_ROOT=/local/path run_plan
    expect_rc 64
    expect_out "DATA_ROOT must be an s3:// URI"
}

plan_tolerates_crlf_input() {
    given_input phs000001 10
    printf 'phs000001\tA\ts3://m/1\r\n' > "$T/studies.tsv"
    run_plan
    expect_rc 0
    expect_eq "$(planned_rows | cut -f2,3,6)" $'phs000001\tA\ts3://m/1'
}

# ---------------------------------------------------------------------------- run-pooled-study.sh

pooled_success_records_metrics_and_debug_locations() {
    run_pooled phs000001 ok
    expect_rc 0
    expect_out "[phs000001] SUCCESS (run build-1-split-phs000001)"
    expect_eq "$(result .outcome)" SUCCESS
    expect_eq "$(result .lane)" pool
    expect_eq "$(result .exitCode)" 0
    expect_eq "$(result .attempts)" 1
    expect_eq "$(result .job.metrics.totalRows)" 10
    expect_eq "$(result .runner.instanceId)" i-0abc1234
    expect_eq "$(result .debug.runnerLog)" "s3://data/etl-runner/logs/split-allconcepts-build-1-split-phs000001.log"
    expect_eq "$(result .debug.workerLog)" "s3://data/etl-runner/logs/split-allconcepts-p1-i-0abc1234.log"
    expect_eq "$(result .failureReason)" null
    [[ -f "$T/out/phs000001/reports/split-allconcepts-build-1-split-phs000001.json" ]] || fail "report not fetched"
    expect_file_has "$T/out/phs000001/validation.txt" "[ok]   at least one row was read"
    # the task handed to the worker carried the study's parameters
    expect_file_has "$STUB_STATE/aws.log" "s3://data/etl-runner/pools/p1/i-0abc1234/task.json"
}

pooled_warnings_are_success_with_warnings() {
    run_pooled phs000001 warn
    expect_rc 10
    expect_eq "$(result .outcome)" SUCCESS_WITH_WARNINGS
    expect_eq "$(result '.validation.warnings | join(",")')" "no unmapped hpds ids"
    expect_out "SUCCESS WITH WARNINGS"
}

pooled_data_error_carries_the_jobs_error_message() {
    run_pooled phs000001 data
    expect_rc 1
    expect_eq "$(result .outcome)" FAILED
    expect_eq "$(result .failureStage)" run
    expect_eq "$(result .exitName)" DATA_ERROR
    expect_eq "$(result .failureReason)" "DATA_ERROR (exit 3): Mapping CSV at s3://b/m.csv contained no data rows"
    expect_eq "$(result .attempts)" 1    # a data error is not retried
    expect_out "[phs000001] FAILED (run build-1-split-phs000001): DATA_ERROR (exit 3)"
    expect_out "runner log: s3://data/etl-runner/logs/split-allconcepts-build-1-split-phs000001.log"
    expect_out "[phs000001]   === runner log ==="   # the monitor tail reaches the console
}

pooled_success_that_fails_validation_is_a_failure_with_the_failed_checks() {
    run_pooled phs000001 validation
    expect_rc 1
    expect_eq "$(result .failureStage)" validation
    expect_eq "$(result .exitCode)" 0
    expect_eq "$(result .failureReason)" \
        "the job reported success but its output failed validation: at least one row was read; at least one consent file produced"
}

pooled_infrastructure_error_is_retried_once_on_a_running_worker() {
    run_pooled phs000001 "infra ok"
    expect_rc 0
    expect_eq "$(result .outcome)" SUCCESS
    expect_eq "$(result .attempts)" 2
    expect_eq "$(result .runId)" build-1-split-phs000001-r2
    expect_out "retrying"
    [[ -f "$T/out/phs000001/monitor-attempt-1.log" && -f "$T/out/phs000001/monitor-attempt-2.log" ]] \
        || fail "a monitor log per attempt"
}

pooled_infrastructure_error_twice_fails_after_two_attempts() {
    run_pooled phs000001 "infra infra"
    expect_rc 1
    expect_eq "$(result .attempts)" 2
    expect_eq "$(result .failureReason)" "INFRASTRUCTURE_ERROR (exit 4): Delete from consents failed: connection refused"
}

pooled_retry_can_be_switched_off() {
    RETRY_ON_INFRA=false run_pooled phs000001 "infra ok"
    expect_rc 1
    expect_eq "$(result .attempts)" 1
}

pooled_worker_that_dies_is_not_retried_and_points_at_its_logs() {
    run_pooled phs000001 "die ok"
    expect_rc 1
    expect_eq "$(result .attempts)" 1
    expect_eq "$(result .exitCode)" 4
    expect_eq "$(result .failureReason)" \
        "INFRASTRUCTURE_ERROR (exit 4): the runner died or lost contact before the job wrote a report"
    expect_out "worker i-0abc1234 is terminated: not retrying (worker log: s3://data/etl-runner/logs/split-allconcepts-p1-i-0abc1234.log)"
    expect_file_has "$T/out/phs000001/monitor-attempt-1.log" "Instance terminated without publishing a status sentinel"
}

pooled_hung_run_times_out_and_retires_its_worker() {
    PIPELINE_TIMEOUT=0 run_pooled phs000001 hang
    expect_rc 2
    expect_eq "$(result .exitName)" TIMEOUT
    expect_eq "$(result .failureReason)" "TIMEOUT (exit 124): timed out waiting for the runner to finish"
    expect_file_has "$STUB_STATE/aws.log" "ec2 terminate-instances --region us-east-1 --instance-ids i-0abc1234"
    expect_out "worker i-0abc1234 terminated: it takes no more studies"
}

pooled_timeout_still_retires_the_worker_when_it_cannot_be_terminated() {
    echo "An error occurred (UnauthorizedOperation) when calling the TerminateInstances operation" \
        > "$STUB_STATE/terminate_error"
    PIPELINE_TIMEOUT=0 run_pooled phs000001 hang
    expect_rc 2
    expect_out "UnauthorizedOperation"
    expect_out "could not terminate worker i-0abc1234; it takes no more studies"
}

pooled_failure_other_than_a_timeout_leaves_the_worker_alone() {
    run_pooled phs000001 data
    expect_rc 1
    if grep -q "terminate-instances" "$STUB_STATE/aws.log"; then fail "terminated the worker"; fi
}

pooled_busy_worker_fails_at_dispatch_without_overwriting_its_task() {
    mkdir -p "$STUB_STATE/s3/data/etl-runner/pools/p1/i-0abc1234"
    echo '{"runId": "other"}' > "$STUB_STATE/s3/data/etl-runner/pools/p1/i-0abc1234/task.json"
    run_pooled phs000001 ok
    expect_rc 1
    expect_eq "$(result .failureStage)" dispatch
    expect_out "still has an unclaimed task"
    expect_eq "$(jq -r .runId "$STUB_STATE/s3/data/etl-runner/pools/p1/i-0abc1234/task.json")" other
}

pooled_dispatch_that_cannot_check_for_a_pending_task_refuses_instead_of_overwriting() {
    echo "pools/p1/i-0abc1234/task.json" > "$STUB_STATE/head_error"
    run_pooled phs000001 ok
    expect_rc 1
    expect_eq "$(result .failureStage)" dispatch
    expect_out "could not check s3://data/etl-runner/pools/p1/i-0abc1234/task.json for an unclaimed task"
    expect_out "(403)"
    # Retried before giving up, and never written.
    expect_eq "$(grep -c "s3api head-object.*task.json" "$STUB_STATE/aws.log")" 3
    [[ ! -e "$STUB_STATE/s3/data/etl-runner/pools/p1/i-0abc1234/task.json" ]] || fail "task.json was written"
}

pooled_requires_its_environment() {
    RC=0; OUT=$(STUDY_ID=phs000001 "$SPLIT/run-pooled-study.sh" "$T/out/x" 2>&1) || RC=$?
    expect_rc 64
    expect_out "INPUT_URI is required"
}

# ---------------------------------------------------------------------------- record-study-result.sh

record_large_lane_reads_the_exit_code_and_run_id_from_the_build_reports() {
    mkdir -p "$T/large/reports"
    jq -n '{runId: "b-split-phs000002-r2", phase: "done", exitCode: 0, exitName: "SUCCESS", instanceId: "i-0big"}' \
        > "$T/large/reports/status.json"
    # a SUCCESS report the way the stub writes one
    jq -n '{jobName: "split-allconcepts", runId: "b-split-phs000002-r2", exitCode: "SUCCESS", status: "SUCCESS",
            errorMessage: null, metrics: {totalRows: 99, rowsPerConsent: {"1": 99}}}' \
        > "$T/large/reports/split-allconcepts-b-split-phs000002-r2.json"
    printf '  [ok]   standard report checks\n' > "$T/large/validation.txt"
    RC=0; OUT=$("$SPLIT/record-study-result.sh" --out "$T/large/study-result.json" --study phs000002 --abv BIG \
        --lane large --run-id b-split-phs000002 --attempts 2 --reports-dir "$T/large/reports" \
        --validation-rc 0 --validation-file "$T/large/validation.txt" --build-url https://jenkins/job/split/7/ 2>&1) || RC=$?
    RESULT="$T/large/study-result.json"
    expect_rc 0
    expect_eq "$OUT" SUCCESS
    expect_eq "$(result .runId)" b-split-phs000002-r2
    expect_eq "$(result .exitCode)" 0
    expect_eq "$(result .job.metrics.totalRows)" 99
    expect_eq "$(result .debug.build)" https://jenkins/job/split/7/
}

record_large_lane_build_that_never_reported_is_a_failure_with_the_given_reason() {
    RC=0; OUT=$("$SPLIT/record-study-result.sh" --out "$T/large/study-result.json" --study phs000002 --abv BIG \
        --lane large --run-id b-split-phs000002 --build-url https://jenkins/job/split/8/ \
        --reason "split-allconcepts #8 ended ABORTED before the runner reported" 2>&1) || RC=$?
    RESULT="$T/large/study-result.json"
    expect_rc 0
    expect_eq "$OUT" FAILED
    expect_eq "$(result .failureStage)" run
    expect_eq "$(result .failureReason)" "split-allconcepts #8 ended ABORTED before the runner reported"
}

record_preflight_and_not_started_outcomes() {
    "$SPLIT/record-study-result.sh" --out "$T/a/study-result.json" --study phs000009 --lane preflight \
        --stage preflight --reason "legacy allConcepts input not found" --input-uri s3://x/in.csv >/dev/null
    "$SPLIT/record-study-result.sh" --out "$T/b/study-result.json" --study phs000010 --lane pool \
        --stage not-started --reason "every runner-pool worker died" >/dev/null
    RESULT="$T/a/study-result.json"; expect_eq "$(result .outcome)" FAILED; expect_eq "$(result .inputUri)" s3://x/in.csv
    RESULT="$T/b/study-result.json"; expect_eq "$(result .outcome)" NOT_STARTED
    expect_eq "$(result .debug.rerun)" "Re-run only this study: STUDY_FILTER=phs000010"
}

record_rejects_missing_arguments() {
    RC=0; OUT=$("$SPLIT/record-study-result.sh" --out "$T/x.json" --study phs000001 2>&1) || RC=$?
    expect_rc 64
}

# ---------------------------------------------------------------------------- split-report.sh

# Results for a mixed stage: two successes (one with warnings), a data error, a validation failure,
# a pre-flight failure and a study that never started.
given_mixed_results() {
    run_pooled phs000001 ok
    run_pooled phs000002 warn
    run_pooled phs000003 data
    run_pooled phs000004 validation
    "$SPLIT/record-study-result.sh" --out "$T/out/phs000005/study-result.json" --study phs000005 --abv "A|B" \
        --lane preflight --stage preflight --reason "legacy allConcepts input not found" >/dev/null
    "$SPLIT/record-study-result.sh" --out "$T/out/phs000006/study-result.json" --study phs000006 --lane pool \
        --stage not-started --reason "every runner-pool worker died" >/dev/null
}
run_report() { RC=0; OUT=$(LARGE_STUDY_GB=5 POOL_WORKERS=3 "$SPLIT/split-report.sh" "$T/out" build-1 "$T/report" 2>&1) || RC=$?; }
J() { jq -r "$1" "$T/report/split-allconcepts-report-build-1.json"; }
MDFILE() { echo "$T/report/split-allconcepts-report-build-1.md"; }

report_all_successful_is_success_and_passes_the_standard_report_checks() {
    run_pooled phs000001 ok
    run_pooled phs000002 ok
    run_report
    expect_rc 0
    expect_eq "$(J .status)" SUCCESS
    expect_eq "$(J .exitCode)" SUCCESS
    expect_eq "$(J .errorMessage)" null
    expect_eq "$(J .metrics.totalRows)" 20
    expect_eq "$(J '.studies | length')" 2
    expect_file_has "$(MDFILE)" "**Result: SUCCESS.** All 2 studies were split and validated."
    RC=0; OUT=$("$COMMON/validate-report.sh" "$T/report/split-allconcepts-report-build-1.json" 2>&1) || RC=$?
    expect_rc 0
}

report_warnings_only_is_success_with_warnings() {
    run_pooled phs000001 ok
    run_pooled phs000002 warn
    run_report
    expect_rc 10
    expect_eq "$(J .exitCode)" SUCCESS_WITH_WARNINGS
    expect_eq "$(J '.outputValidation.issues[0].code')" STUDY_WARNINGS
    expect_file_has "$(MDFILE)" "## Studies with warnings"
    expect_file_has "$(MDFILE)" "| phs000002 | no unmapped hpds ids | 2 | 0 |"
}

report_mixed_stage_is_failed_and_puts_each_failure_first_with_where_to_look() {
    given_mixed_results
    run_report
    expect_rc 1
    expect_eq "$(J .status)" FAILED
    expect_eq "$(J .exitCode)" VALIDATION_FAILED
    expect_eq "$(J .metrics.studies)" 6
    expect_eq "$(J .metrics.failed)" 3
    expect_eq "$(J .metrics.notStarted)" 1
    expect_eq "$(J .metrics.succeededWithWarnings)" 1
    expect_eq "$(J '.metrics.failuresByStage | to_entries | map("\(.key)=\(.value)") | join(",")')" "preflight=1,run=1,validation=1"
    expect_eq "$(J '.inputValidation.issues | map(.location) | join(",")')" phs000005
    # Data failures (run DATA_ERROR, failed validation, no usable input) exclude the study as a
    # warning; only a study that did not start (or failed for another reason) fails the stage.
    expect_eq "$(J '[.outputValidation.issues[] | select(.severity == "ERROR") | .location] | join(",")')" "phs000006"
    expect_eq "$(J '[.outputValidation.issues[] | select(.code == "STUDY_EXCLUDED") | .location] | join(",")')" "phs000003,phs000004"
    expect_eq "$(J '.inputValidation.issues | map(.severity) | join(",")')" WARNING
    expect_eq "$(J '.metrics.excludedStudyIds | join(",")')" "phs000003,phs000004,phs000005"
    expect_eq "$(J '.metrics.rerunStudyIds | join(",")')" phs000006
    expect_eq "$(J .errorMessage)" "1 of 6 studies need a re-run (0 failed for a reason other than their data, 1 did not start): phs000006"
    local md; md=$(MDFILE)
    expect_file_has "$md" "**Result: FAILED.** 1 of 6 studies need a re-run (0 failed for a reason other than their data, 1 did not start); 3 were excluded for data problems."
    expect_file_has "$md" "### phs000003 (ABV): failed in run (data problem: excluded, needs data review)"
    expect_file_has "$md" "- **Job error:** Mapping CSV at s3://b/m.csv contained no data rows"
    expect_file_has "$md" "- **Runner log:** \`s3://data/etl-runner/logs/split-allconcepts-build-1-split-phs000003.log\`"
    expect_file_has "$md" "- **Re-run only this study:** \`STUDY_FILTER=phs000003\`"
    expect_file_has "$md" "- **Failed checks:** at least one row was read; at least one consent file produced"
    expect_file_has "$md" "| phs000006 | every runner-pool worker died |"
    # a run failure shows the job's error, not the generic checks that follow from it
    ! grep -A8 '^### phs000003' "$md" | grep -q 'Failed checks' || fail "run failure lists failed checks"
    # failures come before the full table
    (( $(grep -n '^## Failed studies' "$md" | cut -d: -f1) < $(grep -n '^## All studies' "$md" | cut -d: -f1) )) \
        || fail "failed studies are not listed first"
    # a | inside a value does not break the table
    expect_file_has "$md" '| phs000005 | A\|B |'
    expect_out "phs000003  FAILED  [run] DATA_ERROR (exit 3)"
    expect_out "full report: "
    RC=0; OUT=$("$COMMON/validate-report.sh" "$T/report/split-allconcepts-report-build-1.json" 2>&1) || RC=$?
    expect_rc 1
}

report_data_failures_only_exclude_the_studies_and_succeed_with_warnings() {
    run_pooled phs000001 ok
    run_pooled phs000003 data
    run_pooled phs000004 validation
    "$SPLIT/record-study-result.sh" --out "$T/out/phs000005/study-result.json" --study phs000005 --abv E \
        --lane preflight --stage preflight --reason "legacy allConcepts input not found" >/dev/null
    run_report
    expect_rc 10
    expect_eq "$(J .status)" SUCCESS
    expect_eq "$(J .exitCode)" SUCCESS_WITH_WARNINGS
    expect_eq "$(J .errorMessage)" null
    expect_eq "$(J '.metrics.excludedStudyIds | join(",")')" "phs000003,phs000004,phs000005"
    expect_eq "$(J '.metrics.rerunStudyIds | length')" 0
    expect_eq "$(J '[.inputValidation.issues[], .outputValidation.issues[] | select(.severity == "ERROR")] | length')" 0
    expect_file_has "$(MDFILE)" "3 were excluded for data problems (listed below, and left out of the later stages)."
    # No ERROR issues, so the standard report checks pass; the exclusions are listed as warnings.
    RC=0; OUT=$("$COMMON/validate-report.sh" "$T/report/split-allconcepts-report-build-1.json" 2>&1) || RC=$?
    expect_rc 0
    expect_out "STUDY_EXCLUDED"
}

report_counts_an_unreadable_result_as_a_failure() {
    run_pooled phs000001 ok
    mkdir -p "$T/out/phs000002"; echo "{not json" > "$T/out/phs000002/study-result.json"
    run_report
    expect_rc 1
    expect_eq "$(J '.studies[] | select(.studyId == "phs000002") | .failureStage')" report
}

report_with_no_results_fails() {
    mkdir -p "$T/out"
    run_report
    expect_rc 1
    expect_out "no study-result.json"
}

# ---------------------------------------------------------------------------- run

echo "split-allconcepts stage tests"
for t in $(declare -F | awk '{print $3}' | grep -E '^(plan|pooled|record|report)_'); do
    run_test "$t"
done
echo ""
echo "  $PASSED passed, $FAILED failed"
if (( FAILED > 0 )); then
    printf '  failed: %s\n' "${FAILURES[@]}"
    exit 1
fi
