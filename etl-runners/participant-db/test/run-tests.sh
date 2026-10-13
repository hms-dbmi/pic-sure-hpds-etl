#!/bin/bash
#
# Tests for the participant database scripts (lib.sh, start.sh, backup.sh, ../common/require-db.sh).
#
#   plan_*    the start decision table (plan_start), exhaustively, with no stubs
#   start_*, backup_*, require_*
#             the scripts end to end against stubs/docker and stubs/aws, which simulate the
#             Postgres container and a versioned S3 bucket in a temp directory
#
# Needs bash and jq; no container runtime or AWS access.
#
# Usage: run-tests.sh [name-filter]
# Exit:  0 all passed | 1 any failed
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PDB="$(cd "$HERE/.." && pwd)"
FILTER="${1:-}"
PASSED=0
FAILED=0
FAILURES=()

# ---------------------------------------------------------------------------- harness

setup() {
    T=$(mktemp -d)
    export STUB_STATE="$T/state" PDB_ENV_DIR="$T/env" HPDS_ETL_CREDENTIALS_DIR="$T/creds"
    mkdir -p "$STUB_STATE/s3" "$PDB_ENV_DIR"
    printf 'aws_region = "us-east-1"\nparticipant_db_host = "10.0.0.5"\n' > "$PDB_ENV_DIR/test.tfvars"
    export PATH="$HERE/stubs:$PATH" BUILD_TAG=test-build RESTART_STOPPED_TIMEOUT=2
    unset OVERWRITE RESTORE_FROM IF_LAST_RUN_FAILED
    PREFIX="avillach-73-bdcatalyst-etl/participant-db/test/backups"
    CREDS="$T/creds/participant-db-test.json"
}

teardown() { rm -rf "$T"; }

# A running PostgreSQL <version> container whose app role logs in with the saved credentials.
given_running_db() {
    local version="${1:-160004}"
    echo present > "$STUB_STATE/container"; echo true > "$STUB_STATE/running"
    echo "$version" > "$STUB_STATE/version"
    echo abc123 > "$STUB_STATE/app_password"
    mkdir -p "$T/creds"
    jq -n '{host: "10.0.0.9", port: 5432, dbname: "etl_db", username: "hpds_etl", password: "abc123"}' > "$CREDS"
}
given_stopped_db() { echo present > "$STUB_STATE/container"; echo false > "$STUB_STATE/running"; }
given_backup() { mkdir -p "$STUB_STATE/s3/$PREFIX/$1"; echo "dump" > "$STUB_STATE/s3/$PREFIX/$1/content"; echo "5 x" > "$STUB_STATE/s3/$PREFIX/$1/meta"; }
given_live_state() { mkdir -p "$STUB_STATE/s3/$PREFIX/live_state.json"; echo "{\"state\":\"$1\"}" > "$STUB_STATE/s3/$PREFIX/live_state.json/content"; }
live_state() { jq -r .state "$STUB_STATE/s3/$PREFIX/live_state.json/content" 2>/dev/null || echo none; }
uploaded() { [[ -f "$STUB_STATE/s3/$PREFIX/$1/content" ]] && [[ "$(cat "$STUB_STATE/s3/$PREFIX/$1/content")" == DUMPBYTES ]]; }
restored_from() { grep -q "aws s3 cp s3://bdc-etl-data-d0d6191/$PREFIX/$1 -" "$STUB_STATE/aws.log"; }
recreated() { grep -q '^docker run ' "$STUB_STATE/docker.log"; }
removed() { grep -q "^docker rm -f " "$STUB_STATE/docker.log"; }
touch_log() { touch "$STUB_STATE/docker.log" "$STUB_STATE/aws.log"; }

run_start() { touch_log; RC=0; OUT=$("$PDB/start.sh" test 2>&1) || RC=$?; }
run_backup() { touch_log; RC=0; OUT=$("$PDB/backup.sh" test "$1" 2>&1) || RC=$?; }

fail() { echo "      $*"; return 1; }
expect_rc() { [[ "$RC" == "$1" ]] || fail "exit $RC, expected $1. Output:"$'\n'"$OUT"; }
expect_out() { [[ "$OUT" == *"$1"* ]] || fail "output lacks '$1'. Output:"$'\n'"$OUT"; }
check() { "$@" || fail "failed: $*"; }
refute() { ! "$@" || fail "unexpectedly true: $*"; }

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

# ---------------------------------------------------------------------------- plan_start

plan() {
    # shellcheck source=../lib.sh
    ( source "$PDB/lib.sh"; plan_start "$@"; echo "$PLAN_ACTION $PLAN_PRE_DUMP $PLAN_SOURCE $PLAN_NEXT_STATE" )
}
expect_plan() {
    local want="$1"; shift
    local got; got=$(plan "$@")
    [[ "$got" == "$want" ]] || fail "plan_start $* -> '$got', expected '$want'"
}

plan_keeps_a_clean_db_on_16() { expect_plan "keep no  same" up 16 false "" clean restore-clean; }
plan_keeps_a_db_with_no_recorded_state() { expect_plan "keep no  same" up 16 false "" unknown restore-clean; }
plan_restores_clean_after_a_failed_run_by_default() { expect_plan "replace yes clean clean" up 16 false "" dirty restore-clean; }
plan_keeps_a_failed_runs_data_when_told_to_continue() { expect_plan "keep no  same" up 16 false "" dirty continue; }
plan_overwrite_restores_the_clean_backup() { expect_plan "replace yes clean clean" up 16 true "" clean restore-clean; }
plan_overwrite_restores_the_named_backup() { expect_plan "replace yes x.tar clean" up 16 true "x.tar" clean continue; }
plan_overwrite_beats_continue() { expect_plan "replace yes clean clean" up 16 true "" dirty continue; }
plan_restore_from_without_overwrite_is_ignored_when_up() { expect_plan "keep no  same" up 16 false "x.tar" clean restore-clean; }
plan_wrong_version_carries_the_data_across() { expect_plan "replace yes pre_overwrite same" up 15 false "" clean restore-clean; }
plan_wrong_version_carries_a_failed_run_across_on_continue() { expect_plan "replace yes pre_overwrite same" up 17 false "" dirty continue; }
plan_wrong_version_after_a_failed_run_restores_clean() { expect_plan "replace yes clean clean" up 15 false "" dirty restore-clean; }
plan_absent_starts_from_the_clean_backup() { expect_plan "replace no clean clean" absent "" false "" unknown restore-clean; }
plan_absent_starts_from_the_named_backup() { expect_plan "replace no none clean" absent "" false "none" clean restore-clean; }
plan_absent_after_a_failed_run_with_continue_uses_its_dump() { expect_plan "replace no not_promoted dirty" absent "" false "" dirty continue; }
plan_stopped_tries_to_save_first() { expect_plan "replace try clean clean" down "" false "" clean restore-clean; }
plan_stopped_with_overwrite_uses_the_named_backup() { expect_plan "replace try y.tar clean" down "" true "y.tar" dirty continue; }

# ---------------------------------------------------------------------------- start.sh

start_keeps_a_clean_running_db_and_refreshes_its_host() {
    given_running_db; given_live_state clean
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    refute removed
    check [ "$(jq -r .host "$CREDS")" == 10.0.0.5 ]
    check [ "$(jq -r .password "$CREDS")" == abc123 ]
    check [ "$(stat -f %Lp "$CREDS" 2>/dev/null || stat -c %a "$CREDS")" == 600 ]
}

start_reissues_a_password_when_the_saved_one_is_stale() {
    given_running_db; given_live_state clean
    echo other > "$STUB_STATE/app_password"
    run_start
    expect_rc 0
    expect_out "issuing a new password"
    check [ "$(jq -r .password "$CREDS")" == "$(cat "$STUB_STATE/app_password")" ]
    refute removed
}

start_creates_from_the_clean_backup_when_absent() {
    given_backup participant_db.tar
    run_start
    expect_rc 0
    check recreated
    check restored_from participant_db.tar
    check [ "$(live_state)" == clean ]
    check [ "$(jq -r .password "$CREDS")" == "$(cat "$STUB_STATE/app_password")" ]
    check [ "$(jq -r .container "$CREDS")" == hpds-etl-participant-db-test ]
}

start_creates_a_fresh_schema_when_there_is_no_backup_at_all() {
    run_start
    expect_rc 0
    expect_out "fresh schema"
    check grep -q "CREATE TABLE" "$STUB_STATE/psql.log"
    refute grep -q "aws s3 cp s3://.*participant_db.tar -" "$STUB_STATE/aws.log"
}

start_fails_without_creating_anything_when_s3_cannot_be_checked() {
    touch "$STUB_STATE/head_error"
    run_start
    expect_rc 1
    expect_out "cannot check"
    refute recreated
}

start_restores_clean_after_a_failed_run_saving_the_live_db_first() {
    given_running_db; given_live_state dirty; given_backup participant_db.tar
    run_start
    expect_rc 0
    check uploaded participant_db_pre_overwrite.tar
    check removed
    check restored_from participant_db.tar
    check [ "$(live_state)" == clean ]
}

start_continues_on_a_failed_runs_data_when_told_to() {
    given_running_db; given_live_state dirty
    export IF_LAST_RUN_FAILED=continue
    run_start
    expect_rc 0
    expect_out "continuing from the failed run"
    refute removed
    check [ "$(live_state)" == dirty ]
}

start_overwrite_saves_then_restores_the_named_backup() {
    given_running_db; given_live_state clean; given_backup chosen.tar
    export OVERWRITE=true RESTORE_FROM=chosen.tar
    run_start
    expect_rc 0
    check uploaded participant_db_pre_overwrite.tar
    check restored_from chosen.tar
}

start_overwrite_with_a_missing_backup_changes_nothing() {
    given_running_db; given_live_state clean
    export OVERWRITE=true RESTORE_FROM=missing.tar
    run_start
    expect_rc 1
    expect_out "no backup"
    refute removed
}

start_overwrite_aborts_untouched_when_the_save_fails() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    echo 1 > "$STUB_STATE/pg_dump_rc"
    export OVERWRITE=true
    run_start
    expect_rc 1
    expect_out "pg_dump failed"
    refute removed
}

start_overwrite_aborts_when_the_saved_dump_does_not_verify() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    touch "$STUB_STATE/upload_short"
    export OVERWRITE=true
    run_start
    expect_rc 1
    refute removed
}

start_recreates_a_non_16_server_and_carries_its_data_across() {
    given_running_db 150008; given_live_state clean
    run_start
    expect_rc 0
    expect_out "server is PostgreSQL 15, not 16"
    check uploaded participant_db_pre_overwrite.tar
    check restored_from participant_db_pre_overwrite.tar
    check recreated
    expect_out "(PostgreSQL 16)"
}

start_saves_a_stopped_db_that_comes_back_before_replacing_it() {
    given_stopped_db; given_live_state clean; given_backup participant_db.tar
    run_start
    expect_rc 0
    check uploaded participant_db_pre_overwrite.tar
    check restored_from participant_db.tar
}

start_replaces_a_stopped_db_that_will_not_come_back_with_a_warning() {
    given_stopped_db; given_live_state clean; given_backup participant_db.tar
    echo false > "$STUB_STATE/start_ok"
    run_start
    expect_rc 0
    expect_out "could not be saved"
    check restored_from participant_db.tar
}

start_reports_the_pre_overwrite_dump_when_the_restore_fails() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    echo 1 > "$STUB_STATE/pg_restore_rc"
    export OVERWRITE=true
    run_start
    expect_rc 1
    expect_out "RESTORE_FROM=participant_db_pre_overwrite.tar"
    check [ ! -f "$CREDS" ]
}

start_fails_on_a_dump_with_no_toc() {
    given_backup participant_db.tar
    touch "$STUB_STATE/no_toc"
    run_start
    expect_rc 1
    expect_out "holds no toc.dat"
}

start_refuses_an_unversioned_bucket() {
    echo Suspended > "$STUB_STATE/versioning"
    run_start
    expect_rc 1
    expect_out "not Enabled"
    refute recreated
}

start_rejects_bad_parameters() {
    export OVERWRITE=yes
    run_start
    expect_rc 1
    expect_out "OVERWRITE must be true or false"
    export OVERWRITE=false IF_LAST_RUN_FAILED=maybe
    run_start
    expect_rc 1
    expect_out "IF_LAST_RUN_FAILED must be"
}

# ---------------------------------------------------------------------------- backup.sh

backup_promoted_overwrites_the_clean_restore_point() {
    given_running_db; given_live_state dirty
    run_backup true
    expect_rc 0
    check uploaded participant_db.tar
    refute uploaded participant_db_not_promoted.tar
    check [ "$(live_state)" == clean ]
    expect_out "BACKUP_RESULT file=participant_db.tar promoted=true"
    check grep -q 'docker exec -u postgres hpds-etl-participant-db-test pg_dump .*--format=d.*--schema etl etl_db' "$STUB_STATE/docker.log"
}

backup_not_promoted_leaves_the_clean_restore_point_and_marks_dirty() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    run_backup false
    expect_rc 0
    check uploaded participant_db_not_promoted.tar
    check [ "$(cat "$STUB_STATE/s3/$PREFIX/participant_db.tar/content")" == dump ]
    check [ "$(live_state)" == dirty ]
}

backup_fails_when_the_db_is_down() {
    given_stopped_db
    run_backup true
    expect_rc 1
    expect_out "nothing was dumped"
}

backup_fails_on_an_unversioned_bucket() {
    given_running_db
    echo Suspended > "$STUB_STATE/versioning"
    run_backup true
    expect_rc 1
    expect_out "not Enabled"
}

backup_fails_when_the_upload_does_not_verify_and_keeps_state() {
    given_running_db; given_live_state clean
    touch "$STUB_STATE/upload_short"
    run_backup true
    expect_rc 1
    expect_out "expected 4242 bytes"
    check [ "$(live_state)" == clean ]
}

backup_fails_without_space_to_stage_the_dump() {
    given_running_db
    echo 0 > "$STUB_STATE/free_kb"
    run_backup true
    expect_rc 1
    expect_out "not enough space"
}

backup_fails_when_pg_dump_fails() {
    given_running_db; given_live_state clean
    echo 1 > "$STUB_STATE/pg_dump_rc"
    run_backup true
    expect_rc 1
    check [ "$(live_state)" == clean ]
}

backup_rejects_a_bad_promote_value() {
    given_running_db
    run_backup maybe
    expect_rc 1
    expect_out "promote must be true or false"
}

# ---------------------------------------------------------------------------- require-db.sh

require_passes_when_up_with_credentials() {
    given_running_db
    touch_log
    RC=0; OUT=$("$PDB/../common/require-db.sh" test 2>&1) || RC=$?
    expect_rc 0
    expect_out "Participant database is up"
    [[ "$OUT" != *abc123* ]] || fail "printed the password"
}

require_fails_without_credentials() {
    given_running_db; rm -f "$CREDS"
    touch_log
    RC=0; OUT=$("$PDB/../common/require-db.sh" test 2>&1) || RC=$?
    expect_rc 1
    expect_out "no credentials"
}

require_fails_when_the_container_is_down() {
    given_running_db; echo false > "$STUB_STATE/running"
    touch_log
    RC=0; OUT=$("$PDB/../common/require-db.sh" test 2>&1) || RC=$?
    expect_rc 1
    expect_out "is down"
}

# ---------------------------------------------------------------------------- run

echo "participant-db tests"
for t in $(declare -F | awk '{print $3}' | grep -E '^(plan|start|backup|require)_'); do
    run_test "$t"
done
echo ""
echo "  $PASSED passed, $FAILED failed"
if (( FAILED > 0 )); then
    printf '  failed: %s\n' "${FAILURES[@]}"
    exit 1
fi
