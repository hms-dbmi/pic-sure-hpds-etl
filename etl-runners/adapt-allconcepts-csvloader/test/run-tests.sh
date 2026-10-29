#!/bin/bash
#
# Contract tests of the adapt-allconcepts-csvloader runner. Needs bash, jq, java (the JDK, for javac) and a
# built JAR; no AWS and no network (aws is a stub on PATH that serves local files).
#
#   job_*        runs the JAR on the committed v1 vector (local paths) and compares the output with the
#                vector's expected-partitions.csv and expected-report.json
#   runner_*     puts that output through the runner's OWN gates, vendored unmodified in runner-contract/:
#                  stage-inputs.sh   (the runner host's staging and manifest checks),
#                  read_split_manifest from flow-lib.sh   (the container's manifest checks),
#                  InputCheck.java   (the container's per-file CSV check: record count == rows == non-empty lines)
#                each with a negative case, so a passing gate is known to be a live one
#   preflight_*  preflight.sh with the stub: the mirror-prefix guard, the empty-output check, the one-time
#                version-permission probe
#   validate_*   validate.sh over synthetic reports
#   tfvars_*     nothing Jenkins sets as TF_VAR_* is pinned in a .tfvars
#
# Environment: HPDS_ETL_JAR (default <repo>/target/hpds-etl.jar; built with ./mvnw -DskipTests package if
# missing), CSV_JAR, IO_JAR and CODEC_JAR (commons-csv, commons-io and commons-codec jars for InputCheck; default: the newest
# of each under ~/.m2).
#
# Usage: run-tests.sh [name-filter]
# Exit:  0 all passed | 1 any failed
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$RUNNER/../.." && pwd)"
CONTRACT="$HERE/runner-contract"
VECTOR="$REPO/src/test/resources/adapter-vectors/v1"
FILTER="${1:-}"
PASSED=0
FAILED=0
FAILURES=()

for tool in jq java javac; do
    command -v "$tool" >/dev/null 2>&1 || { echo "run-tests.sh needs $tool on PATH" >&2; exit 1; }
done

JAR="${HPDS_ETL_JAR:-$REPO/target/hpds-etl.jar}"
if [[ ! -f "$JAR" ]]; then
    echo "building $JAR"
    (cd "$REPO" && ./mvnw -B -q -DskipTests package) || { echo "cannot build the JAR" >&2; exit 1; }
fi

# InputCheck needs commons-csv (1.10 and older need nothing else; newer ones also need commons-io and commons-codec).
CSV_JAR="${CSV_JAR:-$(find "$HOME/.m2" -name 'commons-csv-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -n 1)}"
[[ -n "$CSV_JAR" && -f "$CSV_JAR" ]] || { echo "no commons-csv jar found: set CSV_JAR (InputCheck needs it)" >&2; exit 1; }
IO_JAR="${IO_JAR:-$(find "$HOME/.m2" -name 'commons-io-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -n 1)}"
CODEC_JAR="${CODEC_JAR:-$(find "$HOME/.m2" -name 'commons-codec-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -n 1)}"
CLASSPATH_CSV="$CSV_JAR${IO_JAR:+:$IO_JAR}${CODEC_JAR:+:$CODEC_JAR}"

# One adapted output for all tests (the job is deterministic), built once.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
OUT="$WORK/adapted"
JOB_LOG="$WORK/job.log"
JOB_RC=0
ETL_REPORTS_DIR="$WORK/reports" java -jar "$JAR" --job=adapt-allconcepts-csvloader --run-id=contract \
    --manifest="$VECTOR/inputs/allConcepts_manifest.csv" --output="$OUT" >"$JOB_LOG" 2>&1 || JOB_RC=$?

mkdir -p "$WORK/classes"
javac -nowarn -cp "$CLASSPATH_CSV" -d "$WORK/classes" "$CONTRACT/decrypt-check/InputCheck.java" 2>"$WORK/javac.log" \
    || { cat "$WORK/javac.log" >&2; echo "InputCheck.java does not compile" >&2; exit 1; }

# ---------------------------------------------------------------------------- harness

fail() { echo "      $*"; return 1; }
expect_eq() { [[ "$1" == "$2" ]] || fail "got '$1', expected '$2'"; }
expect_rc() { [[ "$RC" == "$1" ]] || fail "exit $RC, expected $1. Output:"$'\n'"$OUT_TEXT"; }
expect_out() { [[ "$OUT_TEXT" == *"$1"* ]] || fail "output lacks '$1'. Output:"$'\n'"$OUT_TEXT"; }

setup() {
    T=$(mktemp -d)
    export STUB_S3="$T/s3" STUB_LOG="$T/aws.log"
    mkdir -p "$STUB_S3"
    : >"$STUB_LOG"
    export PATH="$HERE/stubs:$PATH" AWS_REGION=us-east-1
    unset STUB_VERSIONING STUB_NO_LIST_VERSIONS STUB_NO_GET_VERSION STUB_HEAD_SIZE
}
teardown() { rm -rf "$T"; }

run_test() {
    local name="$1"
    [[ -n "$FILTER" && "$name" != *"$FILTER"* ]] && return
    setup
    ( set -e; "$name" )
    if (( $? == 0 )); then
        echo "  [ok]   $name"; PASSED=$((PASSED + 1))
    else
        echo "  [FAIL] $name"; FAILED=$((FAILED + 1)); FAILURES+=("$name")
    fi
    teardown
}

# The adapted output as the runner would find it in S3: s3://runner-data/adapted/run-1/
publish_to_stub() {
    mkdir -p "$STUB_S3/runner-data/adapted/run-1"
    cp -R "$OUT"/. "$STUB_S3/runner-data/adapted/run-1/"
}

stage() { # runs the vendored stage-inputs.sh over the stub bucket; RC and OUT_TEXT
    RC=0
    OUT_TEXT=$(env SPLIT_URI=s3://runner-data/adapted/run-1/ "$CONTRACT/scripts/stage-inputs.sh" "$T/stage" "$T/stage.json" 2>&1) || RC=$?
}

# ---------------------------------------------------------------------------- job

test_job_exits_zero_and_writes_the_split_layout() {
    expect_eq "$JOB_RC" 0 || { cat "$JOB_LOG"; return 1; }
    for f in phs000000.c1-allConcepts.csv phs000000.c2-allConcepts.csv phs999999.c1-allConcepts.csv split_manifest.json report.json; do
        [[ -s "$OUT/$f" ]] || fail "missing $f"
    done
    [[ ! -e "$OUT/rejects" ]] || fail "a rejects directory without rejects"
}

test_job_output_rows_equal_the_vectors_expected_partitions() {
    : >"$T/actual"
    local f p
    for f in "$OUT"/*-allConcepts.csv; do
        p=$(basename "$f" -allConcepts.csv)
        sed "s/^/\"$p\",/" "$f" >>"$T/actual"
    done
    LC_ALL=C sort "$T/actual" >"$T/actual.sorted"
    LC_ALL=C sort "$VECTOR/expected-partitions.csv" >"$T/expected.sorted"
    diff "$T/expected.sorted" "$T/actual.sorted" || fail "output differs from expected-partitions.csv"
}

test_job_split_manifest_and_report_match_the_expected_report() {
    # every field of expected-report.json must be in both documents
    local doc
    for doc in split_manifest.json report.json; do
        jq -e --slurpfile want "$VECTOR/expected-report.json" '
            def sub($w): . as $a
                | if ($w | type) == "object" then ($w | to_entries | all(.key as $k | .value as $v | ($a | has($k)) and ($a[$k] | sub($v))))
                  elif ($w | type) == "array" then (($a | length) == ($w | length)) and ([range(0; $a | length)] | all(. as $i | $a[$i] | sub($w[$i])))
                  else . == $w end;
            sub($want[0])' "$OUT/$doc" >/dev/null || fail "$doc does not contain expected-report.json"
    done
}

# ---------------------------------------------------------------------------- the runner's gates

test_runner_stage_inputs_accepts_the_output() {
    publish_to_stub
    stage
    expect_rc 0
    expect_eq "$(jq -r '.split.selectedPartitions' "$T/stage.json")" 3
    expect_eq "$(jq -r '.split.manifest.partitionsInManifest' "$T/stage.json")" 3
    expect_eq "$(jq -r '[.split.files[].file] | sort | join(",")' "$T/stage.json")" \
        "phs000000.c1-allConcepts.csv,phs000000.c2-allConcepts.csv,phs999999.c1-allConcepts.csv"
    [[ -s "$T/stage/phs999999.c1-allConcepts.csv" ]] || fail "partition file not staged"
}

test_runner_stage_inputs_refuses_a_global_partition() {
    publish_to_stub
    jq '.partitions[0].kind = "global"' "$OUT/split_manifest.json" >"$STUB_S3/runner-data/adapted/run-1/split_manifest.json"
    stage
    expect_rc 2
    expect_out "global"
}

test_runner_stage_inputs_refuses_a_zero_row_partition_and_a_wrong_file_name() {
    publish_to_stub
    jq '.partitions[1].rows = 0' "$OUT/split_manifest.json" >"$STUB_S3/runner-data/adapted/run-1/split_manifest.json"
    stage
    expect_rc 2
    expect_out "no rows"
    rm -rf "$T/stage"
    jq '.partitions[1].file = "other.csv"' "$OUT/split_manifest.json" >"$STUB_S3/runner-data/adapted/run-1/split_manifest.json"
    stage
    expect_rc 2
    expect_out "must be"
}

# read_split_manifest (flow-lib.sh) in a subshell, over a directory holding the staged output
read_manifest_body() { # runs in the command substitution's subshell, so the runner's die/exit ends only that
    export IN_DIR="$T/in" STATE="$T/state" PARTITIONS="${1:-}" SUFFIX_AWARE=0
    mkdir -p "$STATE/meta"
    PARTS=()
    # shellcheck source=runner-contract/lib.sh
    . "$CONTRACT/lib.sh"
    # shellcheck source=runner-contract/flow-lib.sh
    . "$CONTRACT/flow-lib.sh"
    read_split_manifest
    echo "PARTS=${PARTS[*]}"
    local p
    for p in "${PARTS[@]}"; do echo "ROWS $p $(cat "$STATE/meta/$p.rows")"; done
}

read_manifest() { # [PARTITIONS value] : RC and OUT_TEXT
    RC=0
    OUT_TEXT=$(read_manifest_body "${1:-}" 2>&1) || RC=$?
}

test_runner_read_split_manifest_accepts_the_output() {
    cp -R "$OUT" "$T/in"
    read_manifest
    expect_rc 0
    expect_out "PARTS=phs000000.c1 phs000000.c2 phs999999.c1"
    expect_out "ROWS phs000000.c1 13"
    expect_out "ROWS phs999999.c1 12"
}

test_runner_read_split_manifest_refuses_a_hyphenated_name_a_global_kind_and_a_missing_file() {
    cp -R "$OUT" "$T/in"
    jq '.partitions[0].name = "phs000000-x.c1" | .partitions[0].file = "phs000000-x.c1-allConcepts.csv"' "$OUT/split_manifest.json" >"$T/in/split_manifest.json"
    read_manifest
    expect_rc 2
    expect_out "contains '-'"
    jq '.partitions[0].kind = "global"' "$OUT/split_manifest.json" >"$T/in/split_manifest.json"
    read_manifest
    expect_rc 2
    expect_out "global partition"
    cp "$OUT/split_manifest.json" "$T/in/split_manifest.json"
    rm "$T/in/phs000000.c2-allConcepts.csv"
    read_manifest
    expect_rc 2
    expect_out "is missing"
}

input_check() { # FILE : RC and OUT_TEXT (the JSON)
    RC=0
    OUT_TEXT=$(java -cp "$WORK/classes:$CLASSPATH_CSV" InputCheck "$1" 2>&1) || RC=$?
}

test_runner_input_check_agrees_with_rows_for_every_partition() {
    local name rows f lines
    while IFS=$'\t' read -r name rows; do
        f="$OUT/$name-allConcepts.csv"
        input_check "$f"
        expect_rc 0
        expect_eq "$(jq -r '.rows' <<<"$OUT_TEXT")" "$rows"
        expect_eq "$(jq -r '.errorCount' <<<"$OUT_TEXT")" 0
        lines=$(grep -c . "$f")
        expect_eq "$lines" "$rows"   # flow-lib.sh: non-empty lines == records (no embedded line breaks)
    done < <(jq -r '.partitions[] | [.name, (.rows | tostring)] | @tsv' "$OUT/split_manifest.json")
}

test_runner_input_check_is_live_it_rejects_a_header_a_text_number_and_a_bad_timestamp() {
    printf '"patient_num","p","","x",""\n' >"$T/bad1.csv"
    input_check "$T/bad1.csv"; expect_rc 2
    printf '"1","\xc2\xb5a\xc2\xb5","12L","","0"\n' >"$T/bad2.csv"
    input_check "$T/bad2.csv"; expect_rc 2
    printf '"1","\xc2\xb5a\xc2\xb5","1","","yesterday"\n' >"$T/bad3.csv"
    input_check "$T/bad3.csv"; expect_rc 2
}

# ---------------------------------------------------------------------------- preflight

preflight() { # MANIFEST OUTPUT : RC and OUT_TEXT
    RC=0
    OUT_TEXT=$(cd "$RUNNER" && TF_VAR_manifest_uri="$1" TF_VAR_output_uri="$2" ./preflight.sh 2>&1) || RC=$?
}

given_manifest() { # puts a manifest object into the stub bucket
    mkdir -p "$STUB_S3/bkt/data"
    echo "partition,s3_uri,version_id,last_updated,md5" >"$STUB_S3/bkt/data/allConcepts_manifest.csv"
}

test_preflight_passes_with_a_versioned_manifest_an_empty_output_and_version_permissions() {
    given_manifest
    preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/transforms/adapted/run-1/
    expect_rc 0
    expect_out "role can ListBucketVersions"
    expect_out "role can GetObjectVersion"
    # read-only: the stub fails any write
    ! grep -qE ' (rm|mv|sync|mb|rb|put-object|delete-object)' "$STUB_LOG" || fail "preflight called a write"
}

# a manifest with a global row and two partitions of two studies, whose objects exist in the stub bucket
given_sized_manifest() {
    mkdir -p "$STUB_S3/bkt/data"
    local f
    for f in global.csv a.csv b.csv; do echo x >"$STUB_S3/bkt/data/$f"; done
    cat >"$STUB_S3/bkt/data/allConcepts_manifest.csv" <<'MANIFEST'
partition,s3_uri,version_id,last_updated,md5
global,s3://bkt/data/global.csv,v1,2026-01-01T00:00:00Z,00000000000000000000000000000000
phs000000.c1,s3://bkt/data/a.csv,v2,2026-01-01T00:00:00Z,00000000000000000000000000000000
phs999999.c1,s3://bkt/data/b.csv,v3,2026-01-01T00:00:00Z,00000000000000000000000000000000
MANIFEST
}

test_preflight_passes_when_the_root_volume_holds_twice_the_input_plus_10_gib() {
    given_sized_manifest
    # 3 objects of 2 GiB = 6 GiB; 2 x 6 + 10 = 22 GiB
    STUB_HEAD_SIZE=2147483648 TF_VAR_root_volume_size=22 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 0
    expect_out "in-scope objects: 3, 6.00 GiB; need 2 x that + 10 GiB = 22.00 GiB; root volume: 22 GiB"
    grep -q 'head-object .*--version-id v2' "$STUB_LOG" || fail "the size was not read for the pinned version"
}

test_preflight_refuses_a_root_volume_that_is_too_small_and_counts_only_the_selected_studies() {
    given_sized_manifest
    STUB_HEAD_SIZE=2147483648 TF_VAR_root_volume_size=21 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "[FAIL] root volume (21 GiB) holds 2 x the input"
    expect_out "root volume: 21 GiB"
    # with one study selected only global + that study count: 2 x 4 + 10 = 18 GiB, which fits 21
    STUB_HEAD_SIZE=2147483648 TF_VAR_root_volume_size=21 TF_VAR_studies=phs000000 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 0
    expect_out "in-scope objects: 2, 4.00 GiB"
    TF_VAR_root_volume_size=big preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
}

test_preflight_refuses_an_output_under_the_legacy_bucket_prefix() {
    given_manifest
    preflight s3://bkt/data/allConcepts_manifest.csv s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/general/adapted/
    expect_rc 1
    expect_out "avillach-73-bdcatalyst-etl"
    preflight s3://bkt/data/allConcepts_manifest.csv s3://avillach-73-bdcatalyst-etl/adapted/
    expect_rc 1
}

test_preflight_refuses_an_output_prefix_that_holds_an_object() {
    given_manifest
    mkdir -p "$STUB_S3/bkt/data/transforms/adapted/run-1"
    echo x >"$STUB_S3/bkt/data/transforms/adapted/run-1/old.csv"
    preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/transforms/adapted/run-1/
    expect_rc 1
    expect_out "holds no object"
}

test_preflight_refuses_a_role_that_cannot_read_object_versions() {
    given_manifest
    STUB_NO_LIST_VERSIONS=1 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "[FAIL] role can ListBucketVersions"
    STUB_NO_GET_VERSION=1 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "[FAIL] role can GetObjectVersion"
}

test_preflight_uses_only_the_first_line_of_the_version_listing() {
    # geno 2026-10-29: `--max-items 1 --output text` printed "<id>\nNone" and the probe sent both lines as the id
    given_manifest
    preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 0
    expect_out "[ok]   role can GetObjectVersion"
    ! grep -q 'Invalid version id' <<<"$OUT_TEXT" || fail "the probe sent the pagination line as part of the version id"
}

test_preflight_shows_the_aws_error_when_the_version_read_fails() {
    given_manifest
    STUB_NO_GET_VERSION=1 preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "aws: "
}

test_preflight_refuses_an_unversioned_bucket_a_missing_manifest_and_non_s3_locations() {
    given_manifest
    STUB_VERSIONING=Suspended preflight s3://bkt/data/allConcepts_manifest.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "versioning Enabled"
    preflight s3://bkt/data/nope.csv s3://bkt/data/out/
    expect_rc 1
    expect_out "manifest exists"
    preflight /local/manifest.csv s3://bkt/data/out/
    expect_rc 1
    preflight s3://bkt/data/allConcepts_manifest.csv /local/out
    expect_rc 1
}

# ---------------------------------------------------------------------------- validate

report_for() { # RUN ROWS_INPUT COPIES WRITTEN REJECTED DROPPED BALANCED
    mkdir -p "$T/reports"
    jq -n --arg run "$1" --argjson i "$2" --argjson c "$3" --argjson w "$4" --argjson r "$5" --argjson d "$6" --argjson b "$7" '
        {jobName: "adapt-allconcepts-csvloader", jobType: "MIGRATION", runId: $run, exitCode: "SUCCESS", status: "SUCCESS", durationMillis: 5,
         errorMessage: null, inputValidation: {phase: "input", issues: [], counts: {error: 0, warning: 0, info: 0}},
         outputValidation: {phase: "output", issues: [], counts: {error: 0, warning: 0, info: 0}},
         metrics: {rowsInput: $i, rowsGlobalCopies: $c, rowsWritten: $w, rowsRejected: $r, rowsDropped: $d, balanced: $b,
                   partitions: 3, multiStudyPatients: 1, consentCodesSeen: ["1", "2"]}}' >"$T/reports/adapt-allconcepts-csvloader-$1.json"
}

validate() { RC=0; OUT_TEXT=$(cd "$RUNNER" && ./validate.sh "$T/reports" "$1" 2>&1) || RC=$?; }

test_validate_passes_a_balanced_report_and_the_jobs_own_report() {
    report_for r1 30 2 30 0 2 true
    cp "$OUT/report.json" "$T/reports/adapt-allconcepts-csvloader-r1-report.json"
    validate r1
    expect_rc 0
    expect_out "balanced: input (30) + globalCopies (2)"
}

test_validate_accepts_the_stopgap_drop_keys_of_the_jobs_own_report_and_warns() {
    report_for r4 30 2 30 0 2 true
    jq '.droppedDetail.foreignStudy = 5 | .droppedDetail.consentNotInManifest = 3' "$OUT/report.json" \
        >"$T/reports/adapt-allconcepts-csvloader-r4-report.json"
    validate r4
    expect_rc 10
    expect_out "dropped by option: 5 row(s) under another study's tree, 3 _consents row(s)"
    expect_out "[ok]   the run's own report says ok"
}

test_validate_fails_an_unbalanced_report() {
    report_for r2 30 2 30 0 5 true
    validate r2
    expect_rc 1
    expect_out "[FAIL] balanced"
}

test_validate_fails_when_no_report_was_written() {
    mkdir -p "$T/reports"
    validate r3
    expect_rc 1
}

# ---------------------------------------------------------------------------- terraform inputs

test_tfvars_pin_nothing_that_jenkins_sets_as_a_tf_var() {
    local v
    for v in instance_type root_volume_size manifest_uri output_uri studies max_reject_fraction allow_consent_codes multi_study_ids foreign_study_rows unmatched_consent_rows \
        run_id name_suffix image_tar; do
        if grep -hE "^[[:space:]]*${v}[[:space:]]*=" "$RUNNER"/terraform/*.tfvars; then
            fail "$v is set in a .tfvars; a -var-file value would outrank the TF_VAR_$v Jenkins sets"
        fi
    done
}

test_the_two_stopgap_choices_reach_the_job_through_jenkins_and_terraform() {
    local v p
    for v in "FOREIGN_STUDY_ROWS foreign_study_rows" "UNMATCHED_CONSENT_ROWS unmatched_consent_rows"; do
        set -- $v
        p=$1
        grep -qE "choice\(name: '$p', choices: \['reject', 'drop'\]" "$RUNNER/Jenkinsfile" || fail "Jenkinsfile lacks the $p choice (reject, drop)"
        grep -qE "TF_VAR_$2 +=.*params\.$p" "$RUNNER/Jenkinsfile" || fail "Jenkinsfile does not set TF_VAR_$2 from $p"
        grep -q "variable \"$2\"" "$RUNNER/terraform/variables.tf" || fail "variables.tf lacks $2"
        grep -qE "^[[:space:]]*$2 +=.*var\.$2" "$RUNNER/terraform/main.tf" || fail "main.tf passes no job param $2"
    done
}

test_the_job_refuses_a_bad_stopgap_value_with_exit_2() {
    RC=0
    OUT_TEXT=$(ETL_REPORTS_DIR="$T/r" java -jar "$JAR" --job=adapt-allconcepts-csvloader --run-id=bad --foreign-study-rows=ignore \
        --manifest="$VECTOR/inputs/allConcepts_manifest.csv" --output="$T/o" 2>&1) || RC=$?
    expect_rc 2
    expect_eq "$(jq -r '[.inputValidation.issues[].code] | join(",")' "$T/r/adapt-allconcepts-csvloader-bad.json")" BAD_FOREIGN_STUDY_ROWS
}

# ---------------------------------------------------------------------------- run

echo "adapt-allconcepts-csvloader contract tests (jar: $JAR)"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
    run_test "$t"
done

echo ""
echo "  $PASSED passed, $FAILED failed"
if (( FAILED > 0 )); then
    printf '  failed: %s\n' "${FAILURES[@]}"
    exit 1
fi
exit 0
