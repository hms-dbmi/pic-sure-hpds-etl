#!/bin/bash
#
# Tests for the generalized runner-pool scripts (the etl-runner module's worker mode):
#
#   dispatch_*   ../pool-dispatch.sh: the task it writes, its input checks, the unclaimed-task guard
#   retire_*     ../pool-retire-worker.sh: terminate, already-gone, failure, usage
#   worker_*     the module's user_data.sh.tpl in worker mode, rendered by terraform and run locally
#                against worker-stubs/ (aws, podman, IMDS, dnf...): task runs, env allow-list,
#                rejections, stop, idle timeout, termination mid-task
#   boot_*       the same script's DB-credential phase (SSM fetch, validation, sslmode), single mode
#
# Runs against stubs (S3 as a directory tree). Needs bash and jq; worker_*/boot_* also need
# terraform on PATH (no providers, no AWS access) and are skipped without it.
#
# Usage: run-tests.sh [name-filter]        (or: make -C etl-runners/runner-pool test)
# Exit:  0 all passed | 1 any failed
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="$(cd "$HERE/.." && pwd)"
FILTER="${1:-}"
PASSED=0
FAILED=0
FAILURES=()

# ---------------------------------------------------------------------------- harness

WORKER=i-0abc1234
MAILBOX=s3://bucket/etl-runner/pools/p1

setup() {
    T=$(mktemp -d)
    export STUB_STATE="$T/state"
    mkdir -p "$STUB_STATE/s3/bucket"
    touch "$STUB_STATE/aws.log"
    export PATH="$HERE/stubs:$PATH" AWS_REGION=us-east-1
    export DISPATCH_CHECK_DELAY=0
    unset DISPATCH_CHECK_ATTEMPTS
}

teardown() { rm -rf "$T"; }

TASK="bucket/etl-runner/pools/p1/$WORKER/task.json"
task_file() { echo "$STUB_STATE/s3/$TASK"; }
task() { jq -r "$1" "$(task_file)"; }

dispatch() { RC=0; OUT=$("$COMMON/pool-dispatch.sh" "$@" 2>&1) || RC=$?; }
retire() { RC=0; OUT=$("$COMMON/pool-retire-worker.sh" "$@" 2>&1) || RC=$?; }

fail() { echo "      $*"; return 1; }
expect_rc() { [[ "$RC" == "$1" ]] || fail "exit $RC, expected $1. Output:"$'\n'"$OUT"; }
expect_out() { [[ "$OUT" == *"$1"* ]] || fail "output lacks '$1'. Output:"$'\n'"$OUT"; }
expect_eq() { [[ "$1" == "$2" ]] || fail "got '$1', expected '$2'"; }
expect_file_has() { grep -qF -- "$2" "$1" 2>/dev/null || fail "$1 lacks '$2':"$'\n'"$(cat "$1" 2>/dev/null)"; }
expect_no_task() { [[ ! -e "$(task_file)" ]] || fail "a task was written: $(cat "$(task_file)")"; }
expect_log_count() {   # <pattern> <n>
    local n; n=$(grep -c -- "$1" "$STUB_STATE/aws.log" || true)
    [[ "$n" == "$2" ]] || fail "aws called $n times matching '$1', expected $2:"$'\n'"$(cat "$STUB_STATE/aws.log")"
}

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

# ---------------------------------------------------------------------------- pool-dispatch.sh

dispatch_writes_a_task_for_any_job_and_prints_where_it_publishes() {
    dispatch "$WORKER" "$MAILBOX" my-module my-job run-1 study_id=phs000001 batch-size=500 'filter=a=b c'
    expect_rc 0
    expect_eq "$(task .runId)" run-1
    expect_eq "$(task .module)" my-module
    expect_eq "$(task .reportsPrefix)" etl-runner/reports/my-module/run-1
    expect_eq "$(task .logKey)" etl-runner/logs/my-module-run-1.log
    expect_eq "$(task '.env | keys | join(",")')" "ETL_JOB,ETL_PARAM_batch_size,ETL_PARAM_filter,ETL_PARAM_study_id,ETL_RUN_ID"
    expect_eq "$(task .env.ETL_JOB)" my-job
    expect_eq "$(task .env.ETL_RUN_ID)" run-1
    expect_eq "$(task .env.ETL_PARAM_batch_size)" 500
    # Only the first '=' splits key from value.
    expect_eq "$(task .env.ETL_PARAM_filter)" "a=b c"
    expect_out "STATUS_URI=s3://bucket/etl-runner/reports/my-module/run-1/status.json"
    expect_out "REPORTS_URI=s3://bucket/etl-runner/reports/my-module/run-1/"
    expect_out "LOG_URI=s3://bucket/etl-runner/logs/my-module-run-1.log"
}

dispatch_with_no_params_and_a_trailing_slash_on_the_mailbox() {
    dispatch "$WORKER" "$MAILBOX/" m j r
    expect_rc 0
    expect_eq "$(task '.env | keys | join(",")')" "ETL_JOB,ETL_RUN_ID"
}

dispatch_removes_a_stale_sentinel_for_the_same_run_id() {
    local stale="$STUB_STATE/s3/bucket/etl-runner/reports/m/r/status.json"
    mkdir -p "$(dirname "$stale")"; echo '{"exitCode": 4}' > "$stale"
    dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 0
    [[ ! -e "$stale" ]] || fail "stale status.json was not removed"
}

dispatch_refuses_when_a_stale_sentinel_cannot_be_removed() {
    local stale="$STUB_STATE/s3/bucket/etl-runner/reports/m/r/status.json"
    mkdir -p "$(dirname "$stale")"; echo '{"exitCode": 4}' > "$stale"
    touch "$STUB_STATE/rm_error"
    dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 1
    expect_out "could not remove the earlier sentinel"
    expect_no_task
}

dispatch_refuses_while_the_worker_has_an_unclaimed_task() {
    mkdir -p "$(dirname "$(task_file)")"; echo '{"runId": "earlier"}' > "$(task_file)"
    dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 1
    expect_out "still has an unclaimed task"
    expect_eq "$(task .runId)" earlier
}

dispatch_retries_a_failed_check_then_proceeds() {
    echo 1 > "$STUB_STATE/head_error"
    dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 0
    # Two for the unclaimed-task check, one confirming no stale sentinel is left.
    expect_log_count "s3api head-object" 3
    expect_eq "$(task .runId)" r
}

dispatch_refuses_when_the_check_keeps_failing() {
    echo 99 > "$STUB_STATE/head_error"
    DISPATCH_CHECK_ATTEMPTS=3 dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 1
    expect_out "could not check"
    expect_out "Forbidden"
    expect_log_count "s3api head-object" 3
    expect_no_task
}

dispatch_fails_when_the_task_cannot_be_written() {
    touch "$STUB_STATE/put_error"
    dispatch "$WORKER" "$MAILBOX" m j r
    expect_rc 1
    expect_out "could not write"
}

dispatch_rejects_bad_identifiers() {
    dispatch i-XYZ "$MAILBOX" m j r;                 expect_rc 1; expect_out "instance id"
    dispatch "$WORKER" /local/path m j r;             expect_rc 1; expect_out "mailbox must be"
    dispatch "$WORKER" s3://bucket/a/../b m j r;      expect_rc 1; expect_out "mailbox must be"
    dispatch "$WORKER" "$MAILBOX" 'm/x' j r;          expect_rc 1; expect_out "may contain only"
    dispatch "$WORKER" "$MAILBOX" m 'j;rm' r;         expect_rc 1; expect_out "may contain only"
    dispatch "$WORKER" "$MAILBOX" m j '';             expect_rc 1; expect_out "may contain only"
    dispatch "$WORKER" "$MAILBOX" m j;                expect_rc 1
    expect_no_task
    expect_log_count "aws " 0
}

dispatch_rejects_bad_parameters() {
    dispatch "$WORKER" "$MAILBOX" m j r novalue;           expect_rc 1; expect_out "must be key=value"
    dispatch "$WORKER" "$MAILBOX" m j r 'bad.key=v';       expect_rc 1; expect_out "parameter name"
    dispatch "$WORKER" "$MAILBOX" m j r '=v';              expect_rc 1; expect_out "parameter name"
    dispatch "$WORKER" "$MAILBOX" m j r $'k=line1\nline2'; expect_rc 1; expect_out "line break"
    dispatch "$WORKER" "$MAILBOX" m j r $'k=v\r';          expect_rc 1; expect_out "line break"
    expect_no_task
}

# ---------------------------------------------------------------------------- pool-retire-worker.sh

retire_terminates_the_worker() {
    retire "$WORKER" "run r timed out"
    expect_rc 0
    expect_out "terminating worker $WORKER: run r timed out"
    expect_log_count "ec2 terminate-instances --region us-east-1 --instance-ids $WORKER" 1
}

retire_treats_an_already_gone_worker_as_retired() {
    echo "An error occurred (InvalidInstanceID.NotFound) when calling the TerminateInstances operation" \
        > "$STUB_STATE/terminate_error"
    retire "$WORKER" gone
    expect_rc 0
}

retire_reports_a_failed_terminate() {
    echo "An error occurred (UnauthorizedOperation)" > "$STUB_STATE/terminate_error"
    retire "$WORKER" why
    expect_rc 1
    expect_out "could not terminate $WORKER"
    expect_out "UnauthorizedOperation"
}

retire_usage() {
    retire;                 expect_rc 64; expect_out "usage"
    retire "$WORKER";       expect_rc 64
    retire not-an-id why;   expect_rc 64
    expect_log_count "aws " 0
}

# ---------------------------------------------------------------------------- user_data.sh.tpl

TPL="$(cd "$COMMON/../../terraform-modules/etl-runner" && pwd)/user_data.sh.tpl"
BUCKET=bdc-etl-data-test

# Renders the template once per variant (worker, single), both with the participant DB, into
# $RENDERED/<variant>.sh. The boot env carries a pool-level ETL_JOB/ETL_RUN_ID/ETL_PARAM_ that a
# task must replace.
render_templates() {
    RENDERED=$(mktemp -d)
    local env_b64
    env_b64=$(printf '%s\n' AWS_REGION=us-east-1 ETL_REPORTS_DIR=/reports ETL_JOB=pool-job ETL_RUN_ID=pool-run \
                LOG_LEVEL=INFO ETL_PARAM_pool_only=x | base64 | tr -d '\n')
    cat > "$RENDERED/main.tf" <<EOF
locals {
  base = {
    aws_region = "us-east-1", stack_s3_bucket = "$BUCKET", module_name = "pool-mod", job_name = "pool-job",
    run_id = "pool-run", image_name = "img", image_tar = "img.tar.gz", uses_db = true,
    db_credentials_parameter = "/hpds-etl/test/participant-db/app-credentials",
    reports_prefix = "etl-runner/reports/pool-mod/pool-run", container_env_b64 = "$env_b64",
    pool_idle_timeout = 1
  }
}
output "worker" { value = templatefile("$TPL", merge(local.base, { pool_prefix = "etl-runner/pools/p1" })) }
output "single" { value = templatefile("$TPL", merge(local.base, { pool_prefix = "" })) }
EOF
    (
        cd "$RENDERED" || exit 1
        export TF_DATA_DIR="$RENDERED/.tf"
        terraform init -backend=false -input=false >/dev/null \
            && terraform apply -auto-approve -input=false >/dev/null \
            && terraform output -raw worker > worker.sh \
            && terraform output -raw single > single.sh
    ) || { echo "could not render $TPL"; return 1; }
}

# Runs a rendered variant with its paths moved under $W and system commands stubbed. Sets RC.
run_boot() {   # run_boot <variant>
    W="$T/w"; export W
    mkdir -p "$W/bin" "$W/queue" "$W/s3/$BUCKET/etl-runner/container"
    echo image | gzip > "$W/s3/$BUCKET/etl-runner/container/img.tar.gz"
    cp "$HERE"/worker-stubs/{aws,podman,curl,sleep} "$W/bin/"
    for c in dnf systemctl shutdown; do cp "$HERE/worker-stubs/noop" "$W/bin/$c"; done
    sed -e "s#/var/log/etl-pipeline.log#$W/log#; s#^WORK=/var/etl#WORK=$W/etl#" \
        -e "s#/opt/srce/startup.config#$W/srce.config#; s#/tmp/image.tar.gz#$W/image.tar.gz#g" \
        "$RENDERED/$1.sh" > "$W/script.sh"
    RC=0
    PATH="$W/bin:$PATH" bash "$W/script.sh" > "$W/stdout" 2>&1 &
    echo $! > "$W/script.pid"
    wait $! || RC=$?
    OUT=$(cat "$W/stdout")
}
db_param() { mkdir -p "$T/w"; printf '%s' "$1" > "$T/w/param"; }
given_db() { db_param '{"engine":"postgres","host":"10.0.0.5","port":5432,"dbname":"etl_db","username":"app","password":"pw"}'; }
queue() {   # queue <name> <json>
    mkdir -p "$T/w/queue"; printf '%s\n' "$2" > "$T/w/queue/$1.json"
}
good_task() {   # good_task <run-id> [extra env json]
    echo "{\"runId\":\"$1\",\"module\":\"mod-a\",\"reportsPrefix\":\"etl-runner/reports/mod-a/$1\",\"logKey\":\"etl-runner/logs/mod-a-$1.log\",\"env\":{\"ETL_JOB\":\"job-a\",\"ETL_RUN_ID\":\"$1\",\"ETL_PARAM_study_id\":\"phs1\"${2:-}}}"
}
s3() { echo "$W/s3/$BUCKET/$1"; }
status_of() { jq -r "$2" "$(s3 "etl-runner/reports/mod-a/$1/status.json")"; }

worker_runs_a_task_and_publishes_it_like_a_single_run() {
    given_db; queue 1 "$(good_task r1)"
    run_boot worker
    expect_rc 0
    expect_eq "$(status_of r1 .exitCode)" 0
    expect_eq "$(status_of r1 .module)" mod-a
    expect_eq "$(status_of r1 .job)" job-a
    expect_eq "$(status_of r1 .instanceId)" i-0abc1234
    [[ -f "$(s3 etl-runner/reports/mod-a/r1/report.json)" ]] || fail "report not published"
    [[ ! -e "$(s3 etl-runner/reports/mod-a/r1/big.csv.partial)" ]] || fail ".partial was published"
    # The task's identity and params replace the pool's; the DB credentials carry over.
    local env="$W/envs/1.env"
    expect_file_has "$env" "ETL_JOB=job-a"
    expect_file_has "$env" "ETL_RUN_ID=r1"
    expect_file_has "$env" "ETL_PARAM_study_id=phs1"
    expect_file_has "$env" "DB_URL=jdbc:postgresql://10.0.0.5:5432/etl_db?sslmode=require"
    expect_file_has "$env" "DB_PASSWORD=pw"
    ! grep -q 'pool-job\|pool-run\|pool_only' "$env" || fail "pool identity leaked into the task env: $(cat "$env")"
    expect_file_has "$(s3 etl-runner/logs/mod-a-r1.log)" "container output 1"
    expect_file_has "$(s3 etl-runner/logs/mod-a-r1.log)" "Task r1: job-a exited 0"
    [[ ! -e "$W/etl/etl.env" && ! -e "$W/etl/task.env" ]] || fail "an env file with credentials was left behind"
    expect_out "Stop requested"
    expect_file_has "$W/calls" "shutdown now"
    [[ -f "$(s3 etl-runner/logs/pool-mod-pool-run-i-0abc1234.log)" ]] || fail "worker log not uploaded"
}

worker_runs_tasks_in_turn_and_reports_each_exit_code() {
    given_db; queue 1 "$(good_task r1)"; queue 2 "$(good_task r2)"
    PODMAN_EXIT=3 run_boot worker
    expect_rc 0
    expect_eq "$(status_of r1 .exitName)" DATA_ERROR
    expect_eq "$(status_of r2 .exitCode)" 3
    expect_file_has "$W/envs/2.env" "ETL_RUN_ID=r2"
}

worker_rejects_disallowed_env_with_a_config_error_and_says_why() {
    given_db
    queue 1 "$(good_task r1 ',"DB_PASSWORD":"x"')"
    queue 2 "$(good_task r2 ',"ETL_PARAM_x":"a\nb"')"
    queue 3 "$(good_task r3 | jq -c '.env.ETL_RUN_ID = "other"')"
    run_boot worker
    expect_rc 0
    expect_eq "$(status_of r1 .exitCode)" 5
    expect_eq "$(status_of r2 .exitCode)" 5
    expect_eq "$(status_of r3 .exitCode)" 5
    expect_file_has "$(s3 etl-runner/logs/mod-a-r1.log)" "disallowed env entries"
    expect_file_has "$(s3 etl-runner/logs/mod-a-r1.log)" "DB_PASSWORD"
    [[ ! -d "$W/envs" ]] || fail "a rejected task reached the container"
}

worker_gives_a_bad_job_a_sentinel_and_drops_unpublishable_tasks() {
    given_db
    queue 1 'not json'
    queue 2 '{"runId":"r0","reportsPrefix":"../escape","logKey":"x.log","env":{"ETL_JOB":"j"}}'
    queue 3 "$(good_task r1 | jq -c '.env.ETL_JOB = "bad job"')"
    queue 4 "$(good_task r2)"
    run_boot worker
    expect_rc 0
    expect_out "dropping a task that is not a JSON object"
    expect_out "dropping a task with a malformed run id"
    expect_eq "$(status_of r1 .exitCode)" 5
    expect_file_has "$(s3 etl-runner/logs/mod-a-r1.log)" "malformed env.ETL_JOB"
    # ...and the worker carries on.
    expect_eq "$(status_of r2 .exitCode)" 0
}

worker_terminated_mid_task_publishes_nothing_for_it() {
    given_db; queue 1 "$(good_task r1)"
    PODMAN_TERM=1 run_boot worker
    expect_out "Terminated during task r1; publishing nothing"
    [[ ! -e "$(s3 etl-runner/reports/mod-a/r1/status.json)" ]] || fail "a sentinel was published"
    expect_file_has "$W/calls" "shutdown now"
}

worker_idles_out() {
    given_db; touch "$T/w/nostop"
    run_boot worker
    expect_rc 0
    expect_out "No task for 1s; shutting down"
    expect_file_has "$W/calls" "shutdown now"
}

worker_without_credentials_stops_at_boot() {
    run_boot worker
    expect_rc 5
    expect_out "ParameterNotFound"
    expect_out "participant-db-start"
    ! grep -q 'task.json' "$W/calls" || fail "polled for tasks without credentials"
}

single_status() { jq -r "$1" "$(s3 etl-runner/reports/pool-mod/pool-run/status.json)"; }

boot_rejects_credentials_without_a_real_password() {
    local creds
    for creds in '{"host":"h","dbname":"d","username":"u"}' '{"host":"h","dbname":"d","username":"u","password":"null"}' \
                 '{"host":"h","dbname":"d","username":"","password":"p"}' 'not json'; do
        rm -rf "$T/w"; db_param "$creds"
        run_boot single
        expect_rc 5
        expect_out "is not JSON with a non-empty username and password"
        expect_eq "$(single_status .phase)" credentials
    done
}

boot_adds_sslmode_to_the_jdbc_url() {
    db_param '{"url":"jdbc:postgresql://h:5432/d?ApplicationName=x","username":"u","password":"p"}'
    run_boot single
    expect_rc 0
    expect_file_has "$W/envs/1.env" "DB_URL=jdbc:postgresql://h:5432/d?ApplicationName=x&sslmode=require"
    expect_eq "$(single_status .exitCode)" 0
    rm -rf "$T/w"
    db_param '{"url":"jdbc:postgresql://h/d?sslmode=verify-full","username":"u","password":"p"}'
    run_boot single
    expect_file_has "$W/envs/1.env" "DB_URL=jdbc:postgresql://h/d?sslmode=verify-full"
}

boot_reports_a_missing_parameter_as_a_config_error() {
    run_boot single
    expect_rc 5
    expect_eq "$(single_status .exitName)" CONFIG_ERROR
    expect_eq "$(grep -c 'ssm get-parameter' "$W/calls")" 4
}

# ---------------------------------------------------------------------------- main

PATTERN='^(dispatch|retire)_'
if command -v terraform >/dev/null 2>&1 && render_templates; then
    PATTERN='^(dispatch|retire|worker|boot)_'
else
    echo "(terraform not on PATH or render failed: skipping worker_* and boot_*)"
fi
echo "pool scripts:"
for t in $(declare -F | awk '{print $3}' | grep -E "$PATTERN"); do
    run_test "$t"
done
[[ -n "${RENDERED:-}" ]] && rm -rf "$RENDERED"
echo
echo "$PASSED passed, $FAILED failed"
if (( FAILED > 0 )); then
    printf '  failed: %s\n' "${FAILURES[@]}"
    exit 1
fi
