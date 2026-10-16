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
    export PATH="$HERE/stubs:$PATH" BUILD_TAG=test-build RESTART_STOPPED_TIMEOUT=2 AWS_RETRY_SLEEP=0
    unset OVERWRITE RESTORE_FROM IF_LAST_RUN_FAILED RUN_ID
    PREFIX="avillach-73-bdcatalyst-etl/participant-db/test/backups"
    # The stub aws keeps SSM parameter /hpds-etl/test/participant-db/app-credentials here.
    CREDS="$STUB_STATE/ssm/_hpds-etl_test_participant-db_app-credentials"
}

teardown() { rm -rf "$T"; }

# A running PostgreSQL <version> container whose app role logs in with the saved credentials.
given_running_db() {
    local version="${1:-160004}"
    echo present > "$STUB_STATE/container"; echo true > "$STUB_STATE/running"; touch "$STUB_STATE/volume"
    echo "$version" > "$STUB_STATE/version"
    echo on > "$STUB_STATE/ssl"; echo /etc/pdb/pg_hba.conf > "$STUB_STATE/hba_file"; touch "$STUB_STATE/conf_volume"
    echo abc123 > "$STUB_STATE/app_password"
    mkdir -p "$STUB_STATE/ssm"
    jq -n '{engine: "postgres", host: "10.0.0.9", port: 5432, dbname: "etl_db", schema: "etl", username: "hpds_etl",
            password: "abc123", container: "hpds-etl-participant-db-test"}' > "$CREDS"
}
given_stopped_db() { echo present > "$STUB_STATE/container"; echo false > "$STUB_STATE/running"; touch "$STUB_STATE/volume"; }
# A data volume whose container is gone.
given_orphaned_volume() { touch "$STUB_STATE/volume"; }
volume_kept() { [[ -f "$STUB_STATE/volume" ]] && ! grep -q '^docker volume rm' "$STUB_STATE/docker.log"; }
# How many times <key> was uploaded (the versioned bucket's version count).
versions() { cat "$STUB_STATE/s3/$PREFIX/$1/versions" 2>/dev/null || echo 0; }
live_field() { jq -r ".$1 // empty" "$STUB_STATE/s3/$PREFIX/live_state.json/content"; }
given_backup() { mkdir -p "$STUB_STATE/s3/$PREFIX/$1"; echo "dump" > "$STUB_STATE/s3/$PREFIX/$1/content"; echo "5 x" > "$STUB_STATE/s3/$PREFIX/$1/meta"; }
given_live_state() { mkdir -p "$STUB_STATE/s3/$PREFIX/live_state.json"; echo "{\"state\":\"$1\"}" > "$STUB_STATE/s3/$PREFIX/live_state.json/content"; }
live_state() { jq -r .state "$STUB_STATE/s3/$PREFIX/live_state.json/content" 2>/dev/null || echo none; }
uploaded() { [[ -f "$STUB_STATE/s3/$PREFIX/$1/content" ]] && [[ "$(cat "$STUB_STATE/s3/$PREFIX/$1/content")" == DUMPBYTES ]]; }
restored_from() { grep -q "aws s3 cp s3://bdc-etl-data-d0d6191/$PREFIX/$1 -" "$STUB_STATE/aws.log"; }
recreated() { grep -q '^docker run -d ' "$STUB_STATE/docker.log"; }
published() { jq -r ".$1" "$CREDS"; }
# The password the app role has now.
role_password() { cat "$STUB_STATE/app_password"; }
run_require() { touch_log; RC=0; OUT=$("$PDB/../common/require-db.sh" test 2>&1) || RC=$?; }
removed() { grep -q "^docker rm -f " "$STUB_STATE/docker.log"; }
touch_log() { touch "$STUB_STATE/docker.log" "$STUB_STATE/aws.log" "$STUB_STATE/psql.log"; }

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
plan_unfinished_replace_is_retried_unsaved() { expect_plan "replace no s3://b/x.tar dirty" up 16 false "" replacing restore-clean s3://b/x.tar dirty; }
plan_unfinished_replace_is_never_kept_on_continue() { expect_plan "replace no s3://b/x.tar clean" up 16 false "" replacing continue s3://b/x.tar clean; }
plan_unfinished_replace_restores_restore_from_unsaved() { expect_plan "replace no pre_overwrite clean" up 16 true pre_overwrite replacing restore-clean s3://b/x.tar dirty; }
plan_unfinished_replace_when_down_does_not_try_to_save() { expect_plan "replace no clean clean" down "" false "" replacing restore-clean "" ""; }
plan_unfinished_replace_on_another_version_is_not_carried_across() { expect_plan "replace no none clean" up 15 false "" replacing restore-clean none clean; }

# ---------------------------------------------------------------------------- start.sh

start_keeps_a_clean_running_db_and_refreshes_its_host() {
    given_running_db; given_live_state clean
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    refute removed
    check [ "$(published host)" == 10.0.0.5 ]
    # Standalone (no RUN_ID): a password that works is not rotated.
    check [ "$(published password)" == abc123 ]
    check [ "$(role_password)" == abc123 ]
}

start_reissues_a_password_when_the_saved_one_is_stale() {
    given_running_db; given_live_state clean
    echo other > "$STUB_STATE/app_password"
    run_start
    expect_rc 0
    expect_out "is rejected; issuing a new one"
    check [ "$(published password)" == "$(role_password)" ]
    check [ "$(role_password)" != other ]
    refute removed
}

start_creates_from_the_clean_backup_when_absent() {
    given_backup participant_db.tar
    run_start
    expect_rc 0
    check recreated
    check restored_from participant_db.tar
    check [ "$(live_state)" == clean ]
    check [ "$(published password)" == "$(role_password)" ]
    check [ "$(published container)" == hpds-etl-participant-db-test ]
    check [ "$(published engine)" == postgres ]
    check [ "$(published dbname)" == etl_db ]
    check [ "$(published schema)" == etl ]
    check [ "$(published username)" == hpds_etl ]
    check [ "$(published port)" == 5432 ]
    check [ "$(published host)" == 10.0.0.5 ]
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

start_keeps_a_stopped_db_that_will_not_come_back_and_fails() {
    given_stopped_db; given_live_state clean; given_backup participant_db.tar
    echo false > "$STUB_STATE/start_ok"
    run_start
    expect_rc 1
    expect_out "could not be saved. Nothing was removed"
    expect_out "OVERWRITE=true"
    refute removed
    check volume_kept
    check [ "$(live_state)" == clean ]
}

start_keeps_a_running_db_that_is_not_accepting_yet_and_fails() {
    # Running but still in crash recovery (e.g. after a host reboot): never removed unsaved.
    given_running_db; echo false > "$STUB_STATE/accepting"; given_live_state dirty; given_backup participant_db.tar
    run_start
    expect_rc 1
    expect_out "Nothing was removed"
    refute removed
    check volume_kept
    check [ "$(live_state)" == dirty ]
}

start_overwrite_discards_a_stopped_db_that_will_not_come_back() {
    given_stopped_db; given_live_state clean; given_backup participant_db.tar
    echo false > "$STUB_STATE/start_ok"
    export OVERWRITE=true
    run_start
    expect_rc 0
    expect_out "OVERWRITE=true, so its unsaved data"
    check removed
    check restored_from participant_db.tar
}

start_saves_an_orphaned_volume_before_replacing_it() {
    given_orphaned_volume; given_live_state clean; given_backup participant_db.tar
    run_start
    expect_rc 0
    expect_out "absent but its volume"
    check uploaded participant_db_pre_overwrite.tar
    # The container run on the old volume came before anything was removed.
    check [ "$(grep -n -m1 '^docker run -d ' "$STUB_STATE/docker.log" | cut -d: -f1)" -lt "$(grep -n -m1 '^docker volume rm' "$STUB_STATE/docker.log" | cut -d: -f1)" ]
    check restored_from participant_db.tar
}

start_keeps_an_orphaned_volume_that_will_not_come_up_and_fails() {
    given_orphaned_volume; given_live_state clean; given_backup participant_db.tar
    touch "$STUB_STATE/run_not_accepting"
    run_start
    expect_rc 1
    expect_out "Nothing was removed"
    refute removed
    check volume_kept
}

start_creates_a_volume_only_when_there_is_none() {
    given_backup participant_db.tar
    run_start
    expect_rc 0
    refute grep -q "absent but its volume" <<<"$OUT"
    check grep -q '^docker volume create' "$STUB_STATE/docker.log"
}

# DB-H1: the documented recovery after a failed replace must restore the replaced database, not a
# dump of the partial restore saved over it.
start_recovery_after_a_failed_replace_restores_the_replaced_db() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    echo 1 > "$STUB_STATE/pg_restore_rc"
    export OVERWRITE=true
    run_start
    expect_rc 1
    check [ "$(live_state)" == replacing ]
    check [ "$(versions participant_db_pre_overwrite.tar)" == 1 ]
    rm -f "$STUB_STATE/pg_restore_rc"
    : > "$STUB_STATE/docker.log"; : > "$STUB_STATE/aws.log"
    export RESTORE_FROM=participant_db_pre_overwrite.tar
    run_start
    expect_rc 0
    expect_out "partial restore"
    check [ "$(versions participant_db_pre_overwrite.tar)" == 1 ]
    refute grep -q 'pg_dump' "$STUB_STATE/docker.log"
    check restored_from participant_db_pre_overwrite.tar
    check [ "$(live_state)" == clean ]
}

start_retries_a_failed_replace_from_its_source_without_saving() {
    given_running_db; given_live_state dirty; given_backup participant_db.tar; given_backup chosen.tar
    echo 1 > "$STUB_STATE/pg_restore_rc"
    export OVERWRITE=true RESTORE_FROM=chosen.tar
    run_start
    expect_rc 1
    check [ "$(live_field source)" == "s3://bdc-etl-data-d0d6191/$PREFIX/chosen.tar" ]
    rm -f "$STUB_STATE/pg_restore_rc"
    : > "$STUB_STATE/docker.log"; : > "$STUB_STATE/aws.log"
    unset OVERWRITE RESTORE_FROM
    run_start
    expect_rc 0
    expect_out "its restore is retried"
    check [ "$(versions participant_db_pre_overwrite.tar)" == 1 ]
    check restored_from chosen.tar
    check [ "$(live_state)" == clean ]
}

start_overwrite_refuses_to_restore_the_file_its_save_would_overwrite() {
    given_running_db; given_live_state clean; given_backup participant_db_pre_overwrite.tar
    export OVERWRITE=true RESTORE_FROM=participant_db_pre_overwrite.tar
    run_start
    expect_rc 1
    expect_out "Copy it under another name"
    refute removed
    check [ "$(versions participant_db_pre_overwrite.tar)" == 0 ]
}

# DB-M2: only a missing live_state.json means "no recorded state".
start_fails_when_the_live_state_cannot_be_read() {
    given_running_db; given_live_state dirty
    echo live_state.json > "$STUB_STATE/get_error_key"
    run_start
    expect_rc 1
    expect_out "cannot read"
    expect_out "AccessDenied"
    refute removed
    check [ "$(live_state)" == dirty ]
}

start_fails_when_the_live_state_cannot_be_checked() {
    given_running_db; given_live_state dirty
    echo live_state.json > "$STUB_STATE/head_error_key"
    run_start
    expect_rc 1
    expect_out "cannot check"
    check [ "$(live_state)" == dirty ]
}

start_fails_on_an_unreadable_live_state_record() {
    given_running_db; given_live_state clean
    echo 'not json' > "$STUB_STATE/s3/$PREFIX/live_state.json/content"
    run_start
    expect_rc 1
    expect_out "records no known state"
    refute removed
}

start_treats_a_missing_live_state_as_unrecorded() {
    given_running_db
    run_start
    expect_rc 0
    expect_out "live data is unknown"
    check [ "$(live_state)" == clean ]
}

# ORCH-M3: a pipeline run's start records the database dirty; only its promoted backup cleans it.
start_for_a_run_marks_a_kept_db_dirty_until_a_promoted_backup() {
    given_running_db; given_live_state clean
    export RUN_ID=jenkins-hpds-etl-42-db
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    check [ "$(live_state)" == dirty ]
    check grep -q "jenkins-hpds-etl-42-db" <<<"$(live_field reason)"
    # The run is killed before its backup: the next run restores the clean backup.
    given_backup participant_db.tar
    export RUN_ID=jenkins-hpds-etl-43-db
    run_start
    expect_rc 0
    expect_out "the last run failed"
    check restored_from participant_db.tar
    check [ "$(live_state)" == dirty ]
    # This one finishes with a promoted backup: the next run keeps the database.
    run_backup true
    expect_rc 0
    check [ "$(live_state)" == clean ]
    : > "$STUB_STATE/docker.log"
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    refute removed
}

start_for_a_run_marks_a_replaced_db_dirty() {
    given_backup participant_db.tar
    export RUN_ID=jenkins-hpds-etl-44-db
    run_start
    expect_rc 0
    check recreated
    check [ "$(live_state)" == dirty ]
}

start_without_a_run_id_leaves_a_clean_db_clean() {
    given_running_db; given_live_state clean
    run_start
    expect_rc 0
    check [ "$(live_state)" == clean ]
}

start_reports_the_pre_overwrite_dump_when_the_restore_fails() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    echo 1 > "$STUB_STATE/pg_restore_rc"
    export OVERWRITE=true
    run_start
    expect_rc 1
    expect_out "RESTORE_FROM=participant_db_pre_overwrite.tar"
    # The published credentials are the replaced database's: the check before a runner fails.
    run_require
    expect_rc 1
    expect_out "do not log in"
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

# DB-MED-3: credentials rotate per run and are published to SSM.
start_for_a_run_rotates_the_password_of_a_kept_db_and_publishes_it() {
    given_running_db; given_live_state clean
    export RUN_ID=jenkins-hpds-etl-50-db
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    refute removed
    check [ "$(role_password)" != abc123 ]
    check [ "$(published password)" == "$(role_password)" ]
    check [ "$(published host)" == 10.0.0.5 ]
    check grep -q "ALTER ROLE \"hpds_etl\" PASSWORD" "$STUB_STATE/psql.log"
    # Published as an overwritten SecureString, the value through a file, never on the command line.
    check grep -q "aws ssm put-parameter .*--name /hpds-etl/test/participant-db/app-credentials --type SecureString --overwrite" "$STUB_STATE/aws.log"
    refute grep -q "$(role_password)" "$STUB_STATE/aws.log"
    refute grep -q "$(role_password)" "$STUB_STATE/docker.log"
    [[ "$OUT" != *"$(role_password)"* ]] || fail "printed the password"
}

start_for_a_run_rotates_again_on_the_next_run() {
    given_running_db; given_live_state clean
    export RUN_ID=run-1
    run_start
    expect_rc 0
    local first; first=$(role_password)
    run_backup true
    export RUN_ID=run-2
    run_start
    expect_rc 0
    check [ "$(role_password)" != "$first" ]
    check [ "$(published password)" == "$(role_password)" ]
}

start_replacing_publishes_the_new_password() {
    given_running_db; given_live_state clean; given_backup participant_db.tar
    export OVERWRITE=true
    run_start
    expect_rc 0
    check [ "$(role_password)" != abc123 ]
    check [ "$(published password)" == "$(role_password)" ]
}

start_issues_a_password_when_none_is_published() {
    given_running_db; given_live_state clean; rm -f "$CREDS"
    run_start
    expect_rc 0
    expect_out "No usable credentials published"
    check [ "$(published password)" == "$(role_password)" ]
}

# DB-M1: a transient failure must not rotate the password under running jobs.
start_does_not_rotate_on_a_connection_failure() {
    given_running_db; given_live_state clean
    touch "$STUB_STATE/conn_error"
    run_start
    expect_rc 1
    expect_out "Connection refused"
    expect_out "The password was not changed"
    check [ "$(role_password)" == abc123 ]
    check [ "$(published password)" == abc123 ]
    refute grep -q "ALTER ROLE \"hpds_etl\" PASSWORD" "$STUB_STATE/psql.log"
}

start_does_not_rotate_when_ssm_cannot_be_read() {
    given_running_db; given_live_state clean
    touch "$STUB_STATE/ssm_get_error"
    run_start
    expect_rc 1
    expect_out "cannot read SSM parameter"
    check [ "$(role_password)" == abc123 ]
    # Retried before giving up.
    check [ "$(grep -c 'aws ssm get-parameter' "$STUB_STATE/aws.log")" == 4 ]
}

start_fails_when_the_credentials_cannot_be_published() {
    given_running_db; given_live_state clean
    touch "$STUB_STATE/ssm_put_error"
    export RUN_ID=run-3
    run_start
    expect_rc 1
    expect_out "could not publish the connection details to SSM parameter"
    check [ "$(grep -c 'aws ssm put-parameter' "$STUB_STATE/aws.log")" == 4 ]
    # Not recorded as an in-progress run either: the start never finished.
    check [ "$(live_state)" == clean ]
}

start_fails_when_a_restore_cannot_publish_its_credentials() {
    given_backup participant_db.tar
    touch "$STUB_STATE/ssm_put_error"
    run_start
    expect_rc 1
    expect_out "could not publish"
    check [ "$(live_state)" == replacing ]
}

start_retries_a_throttled_publish() {
    given_running_db; given_live_state clean
    echo 2 > "$STUB_STATE/ssm_put_fail_times"
    export RUN_ID=run-4
    run_start
    expect_rc 0
    expect_out "attempt 1 of 4"
    check [ "$(published password)" == "$(role_password)" ]
}

start_removes_the_old_local_credentials_file() {
    given_running_db; given_live_state clean
    mkdir -p "$HPDS_ETL_CREDENTIALS_DIR"; echo '{"password":"old"}' > "$HPDS_ETL_CREDENTIALS_DIR/participant-db-test.json"
    run_start
    expect_rc 0
    check [ ! -f "$HPDS_ETL_CREDENTIALS_DIR/participant-db-test.json" ]
}

# DB-MED-4: TLS, and the superuser kept off the network.
start_creates_a_container_with_tls_and_no_superuser_password_on_the_cli() {
    given_backup participant_db.tar
    run_start
    expect_rc 0
    local run; run=$(grep '^docker run -d ' "$STUB_STATE/docker.log")
    check grep -q -- "-c ssl=on -c ssl_cert_file=/etc/pdb/server.crt -c ssl_key_file=/etc/pdb/server.key" <<<"$run"
    check grep -q -- "-c hba_file=/etc/pdb/pg_hba.conf" <<<"$run"
    check grep -q -- "-e POSTGRES_PASSWORD_FILE=/etc/pdb/superuser-password" <<<"$run"
    check grep -q -- "-v hpds-etl-participant-db-test-conf:/etc/pdb:ro" <<<"$run"
    refute grep -q -- "POSTGRES_PASSWORD=" "$STUB_STATE/docker.log"
    # The config volume is prepared (certificate, password file, pg_hba) before the server starts.
    check [ "$(grep -n -m1 '^docker run --rm ' "$STUB_STATE/docker.log" | cut -d: -f1)" -lt "$(grep -n -m1 '^docker run -d ' "$STUB_STATE/docker.log" | cut -d: -f1)" ]
    check grep -q 'openssl req' "$STUB_STATE/docker.log"
    check grep -q 'chmod 600 server.key superuser-password' "$STUB_STATE/docker.log"
    check grep -q 'chown postgres:postgres server.key' "$STUB_STATE/docker.log"
    # pg_hba: the superuser only locally, the app role only over TLS.
    check grep -Eq '^local +all +postgres +peer$' "$STUB_STATE/pg_hba.conf"
    check grep -Eq '^host +all +postgres +0.0.0.0/0 +reject$' "$STUB_STATE/pg_hba.conf"
    check grep -Eq '^host +all +postgres +::/0 +reject$' "$STUB_STATE/pg_hba.conf"
    check grep -Eq '^hostssl +etl_db +hpds_etl +0.0.0.0/0 +scram-sha-256$' "$STUB_STATE/pg_hba.conf"
    refute grep -Eq '^host +[a-z_]+ +hpds_etl' "$STUB_STATE/pg_hba.conf"
    # The credentials check connects as runners do: TLS required.
    check grep -q 'PGSSLMODE=require' "$STUB_STATE/docker.log"
}

start_recreates_a_kept_container_without_tls_on_the_same_volume() {
    given_running_db; given_live_state clean
    echo off > "$STUB_STATE/ssl"; : > "$STUB_STATE/hba_file"
    run_start
    expect_rc 0
    expect_out "Plan: keep"
    expect_out "its data is kept"
    check recreated
    check volume_kept
    refute grep -q 'pg_dump' "$STUB_STATE/docker.log"
    refute grep -q 'pg_restore' "$STUB_STATE/docker.log"
    check [ "$(cat "$STUB_STATE/ssl")" == on ]
    # The role's password lives in the volume: still the published one.
    check [ "$(published password)" == "$(role_password)" ]
    check [ "$(live_state)" == clean ]
}

start_keeps_a_current_container_as_is() {
    given_running_db; given_live_state clean
    run_start
    expect_rc 0
    refute recreated
    refute grep -q '^docker stop' "$STUB_STATE/docker.log"
}

start_fails_when_the_config_volume_cannot_be_prepared() {
    given_backup participant_db.tar
    echo 1 > "$STUB_STATE/setup_rc"
    run_start
    expect_rc 1
    expect_out "could not prepare the TLS certificate"
    refute recreated
}

# DB-LOW-3: a restore without room to stage fails before downloading.
start_fails_early_without_space_to_restore() {
    given_backup participant_db.tar
    # A 100 MB dump of a 500 MB database needs 2 x 600 MB; 1 GB is free.
    echo "104857600 x 524288000" > "$STUB_STATE/s3/$PREFIX/participant_db.tar/meta"
    echo 1048576 > "$STUB_STATE/free_kb"
    run_start
    expect_rc 1
    expect_out "not enough space in hpds-etl-participant-db-test to restore"
    expect_out "needs about 1200 MB"
    refute restored_from participant_db.tar
    check [ "$(live_state)" == replacing ]
}

start_restores_when_there_is_space() {
    given_backup participant_db.tar
    echo "104857600 x 524288000" > "$STUB_STATE/s3/$PREFIX/participant_db.tar/meta"
    echo 1258291 > "$STUB_STATE/free_kb"
    run_start
    expect_rc 0
    check restored_from participant_db.tar
}

start_assumes_a_dump_without_its_size_restores_larger() {
    given_backup participant_db.tar
    # A 200 MB hand-made dump: assumed 5 x larger restored, so 1200 MB; 1 GB is free.
    echo "209715200 x" > "$STUB_STATE/s3/$PREFIX/participant_db.tar/meta"
    echo 1048576 > "$STUB_STATE/free_kb"
    run_start
    expect_rc 1
    expect_out "needs about 1200 MB"
}

start_checks_restore_space_before_removing_the_live_db() {
    given_running_db; given_live_state clean; given_backup chosen.tar
    echo "104857600 x 524288000" > "$STUB_STATE/s3/$PREFIX/chosen.tar/meta"
    # 100 MB free, plus the live database (pg_database_size stub: 1000 bytes) once removed.
    echo 102400 > "$STUB_STATE/free_kb"
    export OVERWRITE=true RESTORE_FROM=chosen.tar
    run_start
    expect_rc 1
    expect_out "once the live database is removed"
    expect_out "Nothing was changed"
    refute removed
    check [ "$(live_state)" == clean ]
}

backup_records_the_database_size_on_the_dump() {
    given_running_db; given_live_state clean
    run_backup true
    expect_rc 0
    check grep -q -- '--metadata dumped-at=[^ ]*,db-bytes=1000' "$STUB_STATE/aws.log"
    check [ "$(awk '{print $3}' "$STUB_STATE/s3/$PREFIX/participant_db.tar/meta")" == 1000 ]
}

start_fails_before_removing_anything_when_an_s3_source_cannot_be_sized() {
    given_running_db; given_live_state clean
    export OVERWRITE=true RESTORE_FROM=s3://other-bucket/some/missing.tar
    run_start
    expect_rc 1
    expect_out "cannot check s3://other-bucket/some/missing.tar"
    refute removed
}

# Nit: RESTORE_FROM is trimmed without xargs, and validated.
start_trims_restore_from_and_run_id() {
    given_running_db; given_live_state clean; given_backup chosen.tar
    export OVERWRITE=true RESTORE_FROM=$'  chosen.tar \t' RUN_ID='  run 5  '
    run_start
    expect_rc 0
    check restored_from chosen.tar
    check grep -q "run run 5 in progress" <<<"$(live_field reason)"
}

start_rejects_a_quoted_restore_from() {
    given_running_db; given_live_state clean
    export OVERWRITE=true RESTORE_FROM="it's.tar"
    run_start
    expect_rc 1
    expect_out "RESTORE_FROM must be blank"
    refute removed
    export RESTORE_FROM='a b.tar'
    run_start
    expect_rc 1
    expect_out "RESTORE_FROM must be blank"
    export RESTORE_FROM='s3://bucket-only'
    run_start
    expect_rc 1
    expect_out "not an s3://<bucket>/<key> URI"
    export RESTORE_FROM='/local/file.tar'
    run_start
    expect_rc 1
    expect_out "neither a file under backups/ nor an s3:// URI"
    refute removed
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

backup_refuses_a_partial_restore_from_an_unfinished_replace() {
    given_running_db; given_live_state replacing; given_backup participant_db.tar
    run_backup true
    expect_rc 1
    expect_out "partial restore"
    check [ "$(versions participant_db.tar)" == 0 ]
    check [ "$(live_state)" == replacing ]
    run_backup false
    expect_rc 1
    check [ "$(versions participant_db_not_promoted.tar)" == 0 ]
    check [ "$(live_state)" == replacing ]
}

backup_fails_when_the_live_state_cannot_be_read() {
    given_running_db; given_live_state clean
    echo live_state.json > "$STUB_STATE/get_error_key"
    run_backup true
    expect_rc 1
    expect_out "cannot read"
    refute uploaded participant_db.tar
}

start_keeps_a_good_restore_when_recording_it_fails() {
    given_backup participant_db.tar
    # "replacing" is recorded; the write after the restore fails (armed when the container is run).
    touch "$STUB_STATE/fail_live_state_after_run"
    run_start
    expect_rc 1
    expect_out "could not write"
    refute grep -q "was replaced but not brought back up" <<<"$OUT"
    check [ "$(published password)" == "$(role_password)" ]
    check [ "$(live_state)" == replacing ]
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
    run_require
    expect_rc 0
    expect_out "Participant database is up"
    [[ "$OUT" != *abc123* ]] || fail "printed the password"
}

require_fails_without_credentials() {
    given_running_db; rm -f "$CREDS"
    run_require
    expect_rc 1
    expect_out "no usable credentials published"
}

require_fails_when_ssm_cannot_be_read() {
    given_running_db; touch "$STUB_STATE/ssm_get_error"
    run_require
    expect_rc 1
    expect_out "cannot read SSM parameter"
}

require_fails_when_the_published_password_is_stale() {
    given_running_db; echo other > "$STUB_STATE/app_password"
    run_require
    expect_rc 1
    expect_out "do not log in"
}

require_fails_when_the_container_is_down() {
    given_running_db; echo false > "$STUB_STATE/running"
    run_require
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
