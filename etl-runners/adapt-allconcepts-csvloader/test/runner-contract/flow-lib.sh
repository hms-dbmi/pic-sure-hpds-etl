#!/usr/bin/env bash
# shellcheck disable=SC2034  # globals set here (VERIFY_*, NAME_REASON, ...) are read by the flows that source this file
# The container-side runtime that every flow script (flows/*.sh) shares: failure bookkeeping, the report, the key handling and leak
# check, the setup of the mounted directories, the verification of a loaded partition, and what the two split flows have in common
# (split_manifest.json, the input check, the publishing of a partition). Sourced by entrypoint.sh, never executed; nothing runs at
# source time. lib.sh (pure helpers, shared with the Jenkins agent and the runner host) is sourced first.
#
# Never `set -x` here: the key must not reach a log. The key is only ever a file path; its content is never put in a shell
# variable, an environment variable, an argument or a log line.
#
# Globals the entrypoint sets before the first call:
#   FLOW FLOW_LOADER KEY_MODE KEY_FILE IN_DIR OUT_DIR WORK_DIR REPORT_DIR STATE APP_DIR CAPABILITIES_FILE PARTITIONS SPLIT_RUN_ID
#   PARALLEL_JVMS SORT_BUFFER JAVA_EXTRA_OPTS LOADER_JAVA_OPTS DISK_CHECK STARTED_AT KEY_DIR KEY_LEAK KEY_READY SUFFIX_AWARE PARTS
# A flow script defines flow_main (and FLOW_LOADER, flow_resolve for its own checks) and calls the helpers below in the order
# validate -> load -> checks -> manifest.

# ---------------------------------------------------------------------------------------------------------------------------
# Failure bookkeeping. Workers run in other processes, so failures go to a file.
# ---------------------------------------------------------------------------------------------------------------------------
note_fail() { # CODE MESSAGE
    printf '%s\t%s\n' "$1" "$2" >>"$STATE/failures.tsv"
    log "FAIL($1): $2"
}

die() { # CODE MESSAGE
    mkdir -p "$STATE" 2>/dev/null || true
    note_fail "$1" "$2"
    exit "$1"
}

stop_if_failed() { # after a phase: exit with the code of the first recorded failure
    if [ -s "$STATE/failures.tsv" ]; then
        exit "$(sed -n '1s/\t.*//p' "$STATE/failures.tsv")"
    fi
}

# note_warning MESSAGE : a line for report.json "warnings" beyond the ones computed from the partition results
note_warning() {
    printf '%s\n' "$1" >>"$STATE/warnings.txt"
}

# ---------------------------------------------------------------------------------------------------------------------------
# The report: <REPORT_DIR>/report.json, written on every exit.
#   $STATE/report-extra.json (optional, one JSON object) is merged into the top level: a flow puts its own section there
#   ("input", "routing", "limits", ...).
# ---------------------------------------------------------------------------------------------------------------------------
write_report() { # status exit-code
    local status=$1 rc=$2 finished
    [ -d "$REPORT_DIR" ] && [ -w "$REPORT_DIR" ] || return 0
    finished=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    # Large data only through files (--slurpfile / --rawfile), never as an argument: see slurp_dir_to
    if [ -s "$STATE/failures.tsv" ]; then
        jq -R -s 'split("\n") | map(select(length > 0) | split("\t") | {code: (.[0] | tonumber), message: .[1]})' \
            "$STATE/failures.tsv" >"$STATE/failures.json" || echo '[]' >"$STATE/failures.json"
    else
        echo '[]' >"$STATE/failures.json"
    fi
    slurp_dir_to "$STATE/result" "$STATE/results.json" || echo '[]' >"$STATE/results.json"
    [ -s "$STATE/report-extra.json" ] && jq -e 'type == "object"' "$STATE/report-extra.json" >/dev/null 2>&1 || echo '{}' >"$STATE/report-extra.json"
    [ -f "$STATE/warnings.txt" ] || : >"$STATE/warnings.txt"
    jq -n \
        --arg status "$status" --argjson rc "$rc" --arg started "$STARTED_AT" --arg finished "$finished" \
        --arg flow "$FLOW" --arg loader "${FLOW_LOADER:-}" --arg keyMode "$KEY_MODE" --rawfile parts <(printf '%s' "$PARTITIONS") \
        --argjson n "${PARALLEL_JVMS_EFFECTIVE:-1}" \
        --arg sha "$(cap_get "$CAPABILITIES_FILE" pic_sure_sha)" --arg runId "$SPLIT_RUN_ID" \
        --slurpfile failures "$STATE/failures.json" --slurpfile results "$STATE/results.json" --slurpfile extra "$STATE/report-extra.json" \
        --rawfile extraWarnings "$STATE/warnings.txt" --argjson keyLeak "${KEY_LEAK:-null}" '
        $results[0] as $r |
        {schema: 2, flow: $flow, status: $status, exitCode: $rc, startedAt: $started, finishedAt: $finished, loader: $loader, keyMode: $keyMode,
         picSureSha: $sha, splitRunId: $runId, requestedPartitions: $parts, parallelJvms: $n, keyLeak: $keyLeak,
         failures: $failures[0],
         warnings: ([$r[] | select(.zeroTimestamps > 0) | "\(.name): \(.zeroTimestamps) rows have timestamp 0 (loads as 1970-01-01)"]
                  + [$r[] | select(.likelySecondsTimestamps > 0)
                     | "\(.name): \(.likelySecondsTimestamps) rows have a timestamp below 1e11 (epoch seconds, not milliseconds?)"]
                  + [$r[] | select((.skippedRows // 0) > 0) | "\(.name): \(.skippedRows) rows hold no value and stored no observation"]
                  + [$r[] | .name as $name | (.inputWarnings // {}) | to_entries[] | select(.value > 0)
                     | "\($name): \(.value) \(.key) (accepted: the loader skips or tolerates them)"]
                  + ($extraWarnings | split("\n") | map(select(length > 0)))),
         partitions: $r,
         totals: {partitions: ($r | length), rows: ([$r[].rows] | add // 0), observations: ([$r[].observations // 0] | add // 0)}} + $extra[0]' \
        >"$REPORT_DIR/report.json.tmp" && mv "$REPORT_DIR/report.json.tmp" "$REPORT_DIR/report.json"
}

# key_leak_check : scans what we publish for the key text; sets KEY_LEAK to true/false (null = not checked).
# Runs from finish() on every exit, so the logs of a failed run are scanned too.
key_leak_check() {
    local leaked paths=() p f
    [ "${KEY_READY:-0}" = 1 ] || return 0
    for p in "$REPORT_DIR" "$OUT_DIR/manifest.json" "$STATE"; do
        if [ -e "$p" ]; then paths+=("$p"); fi
    done
    if [ "${#paths[@]}" -eq 0 ]; then KEY_LEAK=false; return 0; fi
    leaked=$(grep -rlF -f "$KEY_FILE" -- "${paths[@]}" 2>/dev/null || true)
    if [ -n "$leaked" ]; then
        KEY_LEAK=true
        # remove the files that hold the key, so they cannot be uploaded; name them (not their content) in the failure
        while IFS= read -r f; do rm -f -- "$f"; done <<<"$leaked"
        note_fail 3 "the key text was found in: $(tr '\n' ' ' <<<"$leaked")(deleted)"
    else
        KEY_LEAK=false
    fi
}

finish() {
    local rc=$?
    trap - EXIT
    if [ -d "$STATE" ]; then
        key_leak_check || true
        if [ "${KEY_LEAK:-null}" = true ] && [ "$rc" -eq 0 ]; then rc=3; fi
    fi
    if [ -n "$KEY_DIR" ]; then rm -rf "$KEY_DIR"; fi
    if [ -d "$STATE" ]; then
        if [ "$rc" -ne 0 ] && [ ! -s "$STATE/failures.tsv" ]; then note_fail "$rc" "unexpected failure (exit $rc); see the container log"; fi
        if [ "$rc" -eq 0 ]; then write_report ok 0 || true; else write_report failed "$rc" || true; fi
    fi
    if [ "$rc" -eq 0 ]; then log "done: ok"; else log "done: FAILED (exit $rc); see $REPORT_DIR/report.json"; fi
    exit "$rc"
}

# ---------------------------------------------------------------------------------------------------------------------------
# Java helpers
# ---------------------------------------------------------------------------------------------------------------------------
java_run() { # java options come first via JAVA_OPTS_ARR; usage: java_run ARGS...
    local extra=()
    if [ -n "$JAVA_EXTRA_OPTS" ]; then read -r -a extra <<<"$JAVA_EXTRA_OPTS"; fi
    java "-XX:MaxRAMPercentage=$JAVA_PCT" -XX:+ExitOnOutOfMemoryError ${extra[@]+"${extra[@]}"} "$@"
}

tool() { # TOOL ARGS... (InputCheck, DecryptCheck)
    java_run -cp "$APP_DIR/decrypt-check.jar:$APP_DIR/CSVLoader-jar-with-dependencies.jar" "$@"
}

# ---------------------------------------------------------------------------------------------------------------------------
# Setup of the mounted directories, configuration common to all flows, the key
# ---------------------------------------------------------------------------------------------------------------------------
setup_dirs() {
    local d
    [ -d "$WORK_DIR" ] && [ -w "$WORK_DIR" ] || { echo "WORK_DIR $WORK_DIR is not a writable directory" >&2; exit 5; }
    # A stale tree would mix runs: WORK_DIR and OUT_DIR must start empty (a file system root may hold lost+found).
    if [ -n "$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -not -name lost+found -print -quit)" ]; then
        echo "WORK_DIR $WORK_DIR is not empty" >&2
        exit 5
    fi
    mkdir -p "$STATE/check" "$STATE/result" "$STATE/meta"
    : >"$STATE/failures.tsv"
    trap finish EXIT
    [ -d "$IN_DIR" ] || die 5 "IN_DIR $IN_DIR is not a directory"
    for d in "$OUT_DIR" "$REPORT_DIR"; do
        [ -d "$d" ] && [ -w "$d" ] || die 5 "$d is not a writable directory (mount it)"
    done
    if [ -n "$(find "$OUT_DIR" -mindepth 1 -maxdepth 1 -not -name lost+found -print -quit)" ]; then
        die 5 "OUT_DIR $OUT_DIR is not empty"
    fi
    mkdir -p "$REPORT_DIR/logs" "$REPORT_DIR/concepts"
}

# resolve_common : checks that do not depend on the flow
resolve_common() {
    case $KEY_MODE in secret | ephemeral) ;; *) die 5 "KEY_MODE must be secret or ephemeral (got '$KEY_MODE')" ;; esac
    case $DISK_CHECK in fail | warn) ;; *) die 5 "DISK_CHECK must be fail or warn (got '$DISK_CHECK')" ;; esac
    [ -f "$CAPABILITIES_FILE" ] || die 5 "capabilities file $CAPABILITIES_FILE is missing (is this the hpds-pheno-load image?)"
    [ -f "$APP_DIR/decrypt-check.jar" ] || die 5 "$APP_DIR/decrypt-check.jar is missing"
}

# resolve_jvms : PARALLEL_JVMS for the flow whose loader runs N JVMs; sets PARALLEL_JVMS_EFFECTIVE and JAVA_PCT
resolve_jvms() { # MULTI(1 = CSVLoader style, N JVMs | 0 = one multithreaded JVM)
    is_uint "$PARALLEL_JVMS" && [ "$PARALLEL_JVMS" -ge 1 ] && [ "$PARALLEL_JVMS" -le 64 ] \
        || die 5 "PARALLEL_JVMS must be an integer from 1 to 64 (got '$PARALLEL_JVMS')"
    PARALLEL_JVMS_EFFECTIVE=$PARALLEL_JVMS
    if [ "$1" = 0 ] && [ "$PARALLEL_JVMS" -ne 1 ]; then
        log "PARALLEL_JVMS=$PARALLEL_JVMS ignored: $FLOW_LOADER runs one JVM over one base directory"
        PARALLEL_JVMS_EFFECTIVE=1
    fi
    JAVA_PCT=$((75 / PARALLEL_JVMS_EFFECTIVE))
}

# require_picsureloader : the image must hold a PICSURELoader jar (built from a pic-sure ref that has it)
require_picsureloader() {
    LOADER_JAR="$APP_DIR/PICSURELoader-jar-with-dependencies.jar"
    if [ "$(cap_get "$CAPABILITIES_FILE" picsureloader_present)" != 1 ] || [ ! -f "$LOADER_JAR" ]; then
        die 5 "flow $FLOW needs PICSURELoader but this image has no PICSURELoader jar (built from pic-sure $(cap_get "$CAPABILITIES_FILE" pic_sure_ref) @ $(cap_get "$CAPABILITIES_FILE" pic_sure_sha)); rebuild with a PIC_SURE_REF that contains it"
    fi
}

export_config() {
    export FLOW FLOW_LOADER LOADER_JAR JAVA_PCT JAVA_EXTRA_OPTS LOADER_JAVA_OPTS IN_DIR OUT_DIR WORK_DIR REPORT_DIR STATE SORT_BUFFER APP_DIR KEY_FILE
    export CAPABILITIES_FILE PARALLEL_JVMS_EFFECTIVE
}

prepare_key() {
    if [ "$KEY_MODE" = ephemeral ] && key_file_ok "$KEY_FILE"; then
        log "ephemeral key supplied by the runner (KEY_FILE); the output cannot be served"
    elif [ "$KEY_MODE" = ephemeral ]; then
        [ -d /dev/shm ] && [ -w /dev/shm ] || die 5 "KEY_MODE=ephemeral needs a writable tmpfs at /dev/shm"
        KEY_DIR=$(mktemp -d /dev/shm/hpds-key.XXXXXX)
        chmod 700 "$KEY_DIR"
        KEY_FILE="$KEY_DIR/encryption_key"
        # 24 random bytes -> 32 base64 characters, written straight to the file: never a variable, an argument or a log line
        (umask 077 && head -c 24 /dev/urandom | base64 -w0 >"$KEY_FILE")
        log "ephemeral key generated on tmpfs; the output cannot be served"
    else
        local rc=0
        key_file_ok "$KEY_FILE" || rc=$?
        case $rc in
            0) ;;
            1) die 5 "KEY_MODE=secret but the key file $KEY_FILE is missing or unreadable" ;;
            2) die 5 "the key file $KEY_FILE must hold exactly one line" ;;
            *) die 5 "the key file $KEY_FILE must hold 16, 24 or 32 printable characters on one line" ;;
        esac
    fi
    KEY_READY=1
    export KEY_FILE
}

# ---------------------------------------------------------------------------------------------------------------------------
# Verification of one loaded partition: loader exit, log patterns, files, DecryptCheck, counts.
# Sets VERIFY_ERR (empty = ok) and VERIFY_CONCEPTS / VERIFY_OBS. Reads $STATE/check/<name>.json: rows, and distinctRawConcepts (the
# most concepts the partition may hold; absent = rows, since every stored concept has at least one observation).
# ---------------------------------------------------------------------------------------------------------------------------
verify_partition() { # NAME DIR LOADER_RC LOGFILE
    local p=$1 dir=$2 lrc=$3 logfile=$4 f problems summary mrows distinct
    VERIFY_ERR=""
    VERIFY_CONCEPTS=0
    VERIFY_OBS=0
    mrows=$(jq -r '.rows' "$STATE/check/$p.json")
    distinct=$(jq -r '.distinctRawConcepts // .rows' "$STATE/check/$p.json")
    [ "$lrc" -eq 0 ] || VERIFY_ERR="$VERIFY_ERR; $FLOW_LOADER exited $lrc"
    problems=$(log_problems "$logfile" | tr '\n' ',' | sed 's/,$//')
    [ -z "$problems" ] || VERIFY_ERR="$VERIFY_ERR; log contains: $problems"
    for f in allObservationsStore.javabin columnMeta.javabin columnMeta.csv; do
        [ -s "$dir/$f" ] || VERIFY_ERR="$VERIFY_ERR; $f is missing or empty"
    done
    if [ -z "$VERIFY_ERR" ]; then
        local rc=0
        tool DecryptCheck "$dir" "$KEY_FILE" "$REPORT_DIR/concepts/$p.tsv" >"$REPORT_DIR/logs/$p.decrypt-check.log" 2>&1 || rc=$?
        if [ "$rc" -ne 0 ]; then
            VERIFY_ERR="$VERIFY_ERR; DecryptCheck failed: $(grep -h DECRYPT_CHECK_FAILED "$REPORT_DIR/logs/$p.decrypt-check.log" | sed -n 1p | cut -c1-300)"
        else
            summary=$(grep -h '^DECRYPT_CHECK_OK' "$REPORT_DIR/logs/$p.decrypt-check.log" | sed -n 1p)
            VERIFY_CONCEPTS=$(sed -n 's/.*concepts=\([0-9]*\).*/\1/p' <<<"$summary")
            VERIFY_OBS=$(sed -n 's/.*observations=\([0-9]*\).*/\1/p' <<<"$summary")
            if ! is_uint "$VERIFY_CONCEPTS" || ! is_uint "$VERIFY_OBS"; then
                VERIFY_ERR="$VERIFY_ERR; DecryptCheck printed no summary"
            else
                if [ "$VERIFY_CONCEPTS" -lt 1 ] || [ "$VERIFY_CONCEPTS" -gt "$distinct" ]; then
                    VERIFY_ERR="$VERIFY_ERR; $VERIFY_CONCEPTS concepts stored, expected 1..$distinct (distinct raw concept paths)"
                fi
                if [ "$VERIFY_OBS" -gt "$mrows" ]; then
                    VERIFY_ERR="$VERIFY_ERR; $VERIFY_OBS observations stored from only $mrows rows"
                fi
            fi
        fi
    fi
    VERIFY_ERR=${VERIFY_ERR#; }
    [ -z "$VERIFY_ERR" ]
}

STORE_FILES="allObservationsStore.javabin columnMeta.javabin columnMeta.csv"

# salvage_extras NAME DIR : anything in a loader's partition directory that is not one of the three store files (PICSURELoader
# writes failures.jsonl and concept_rejections.jsonl there) belongs to the report, not to the published store directory
salvage_extras() {
    local p=$1 dir=$2 f
    [ -d "$dir" ] || return 0
    while IFS= read -r f; do
        case " $STORE_FILES " in *" $f "*) continue ;; esac
        mkdir -p "$REPORT_DIR/partitions/$p"
        mv "$dir/$f" "$REPORT_DIR/partitions/$p/$f"
    done < <(find "$dir" -mindepth 1 -maxdepth 1 -type f | sed 's|.*/||' | LC_ALL=C sort)
}

# publish_partition NAME DIR ATTEMPTS : move the store files to OUT_DIR/NAME and write the result record
publish_partition() {
    local p=$1 dir=$2 attempts=$3 f files='[]' size sha
    salvage_extras "$p" "$dir"
    mkdir -p "$OUT_DIR/$p"
    for f in $STORE_FILES; do
        mv "$dir/$f" "$OUT_DIR/$p/$f"
        size=$(wc -c <"$OUT_DIR/$p/$f" | tr -d ' ')
        sha=$(sha256_of "$OUT_DIR/$p/$f")
        files=$(jq -c --arg n "$f" --argjson s "$size" --arg h "$sha" '. + [{name: $n, bytes: $s, sha256: $h}]' <<<"$files")
    done
    jq --argjson obs "$VERIFY_OBS" --argjson concepts "$VERIFY_CONCEPTS" --argjson attempts "$attempts" --argjson files "$files" \
        '. + {status: "ok", observations: $obs, concepts: $concepts, skippedRows: (.rows - $obs), attempts: $attempts, files: $files}' \
        "$STATE/check/$1.json" >"$STATE/result/$1.json"
}

fail_partition() { # NAME ATTEMPTS MESSAGE
    note_fail 3 "partition $1: $3"
    jq --arg err "$3" --argjson attempts "$2" '. + {status: "failed", error: $err, attempts: $attempts, observations: 0, skippedRows: 0}' \
        "$STATE/check/$1.json" >"$STATE/result/$1.json"
}

# ---------------------------------------------------------------------------------------------------------------------------
# The two split flows share these: split_manifest.json, the input check of a partition file, the disk estimate, the workers
# ---------------------------------------------------------------------------------------------------------------------------
read_split_manifest() {
    local m="$IN_DIR/split_manifest.json" ok name kind rows file lc want found r
    [ -f "$m" ] || die 2 "$m is missing: the input must be a split-allconcepts-by-consent output directory"
    jq -e . "$m" >/dev/null 2>&1 || die 2 "split_manifest.json is not valid JSON"
    ok=$(jq -r '
        if (.partitions | type) != "array" or (.partitions | length) == 0 then "partitions must be a non-empty array"
        elif ([.partitions[] | select((.name | type) != "string" or (.name | length) == 0 or (.name | test("[\t\n\r]"))
                                  or (.kind | type) != "string" or (.kind | length) == 0
                                  or (.file | type) != "string" or (.file | length) == 0 or (.file | test("[\t\n\r]"))
                                  or (.rows | type) != "number" or .rows != (.rows | floor) or .rows < 0)] | length) > 0
        then "every partition needs a string name, kind and file and a whole-number rows"
        else "ok" end' "$m")
    [ "$ok" = ok ] || die 2 "split_manifest.json: $ok"
    # D-GLOBAL: global partitions have no consent to be served under; the splitter must have used globals=by-patient.
    if [ -n "$(jq -r '.partitions[] | select(.kind == "global") | .name' "$m" | sed -n 1p)" ]; then
        die 2 "split_manifest.json lists global partition(s) ($(jq -r '[.partitions[] | select(.kind == "global") | .name] | join(",")' "$m")): split with globals=by-patient"
    fi
    lc=$(jq -r '.partitions[].name' "$m" | tr '[:upper:]' '[:lower:]' | LC_ALL=C sort | uniq -d | sed -n 1p)
    [ -z "$lc" ] || die 2 "split_manifest.json: duplicate partition name (case-insensitive): $lc"

    # Select.
    local req=() sel_names=()
    # Only white space counts as "not set"; separators alone (for example ",") are a mistake, not "all partitions".
    if [ -n "${PARTITIONS//[[:space:]]/}" ]; then
        IFS=', ' read -r -a req <<<"$PARTITIONS"
        for r in ${req[@]+"${req[@]}"}; do
            [ -n "$r" ] || continue
            for found in ${sel_names[@]+"${sel_names[@]}"}; do
                [ "$found" != "$r" ] || die 5 "PARTITIONS lists '$r' twice"
            done
            sel_names+=("$r")
        done
        [ "${#sel_names[@]}" -gt 0 ] || die 5 "PARTITIONS is set ('$PARTITIONS') but names no partition"
    fi

    local count=0
    while IFS=$'\t' read -r name kind rows file; do
        if [ "${#sel_names[@]}" -gt 0 ]; then
            want=0
            for r in "${sel_names[@]}"; do [ "$r" != "$name" ] || want=1; done
            [ "$want" = 1 ] || continue
        fi
        [ "$kind" = study ] || die 2 "partition $name has kind '$kind'; only 'study' partitions are loaded"
        if ! valid_partition_name "$name" "$SUFFIX_AWARE"; then
            die 2 "partition name '$name' is refused: $NAME_REASON"
        fi
        [ "$file" = "$name-allConcepts.csv" ] || die 2 "partition $name: file '$file' must be '$name-allConcepts.csv'"
        [ ! -L "$IN_DIR/$file" ] || die 2 "partition $name: $file is a symbolic link"
        [ -f "$IN_DIR/$file" ] || die 2 "partition $name: $file is missing from the input directory"
        [ -s "$IN_DIR/$file" ] || die 2 "partition $name: $file is empty"
        [ "$rows" -gt 0 ] || die 2 "partition $name: the split manifest lists 0 rows"
        printf '%s' "$rows" >"$STATE/meta/$name.rows"
        PARTS+=("$name")
        count=$((count + 1))
    done < <(jq -r '.partitions[] | [.name, .kind, (.rows | tostring), .file] | @tsv' "$m")
    if [ "${#sel_names[@]}" -gt 0 ]; then
        for r in "${sel_names[@]}"; do
            [ -f "$STATE/meta/$r.rows" ] || die 5 "PARTITIONS names '$r', which is not a partition in split_manifest.json"
        done
    fi
    [ "$count" -gt 0 ] || die 5 "no partitions selected"
    log "selected $count partition(s) of $(jq '.partitions | length' "$m") in the split manifest"
}

# Phase 1 (split flows): check input of one partition (worker; always exits 0, the verdict is in the state files)
check_one() {
    local p=$1 file="$IN_DIR/$1-allConcepts.csv" mrows json rc=0 lines blank rows summary
    mrows=$(cat "$STATE/meta/$p.rows")
    json=$(tool InputCheck "$file" 2>"$REPORT_DIR/logs/$p.inputcheck.err") || rc=$?
    if [ -z "$json" ] || ! jq -e . >/dev/null 2>&1 <<<"$json"; then
        note_fail 3 "partition $p: InputCheck produced no result (exit $rc); see logs/$p.inputcheck.err"
        return 0
    fi
    if [ "$rc" -ne 0 ]; then
        summary=$(jq -r '"\(.errorCount) invalid record(s) \(.errorsByKind | tojson); first: "
                         + (.firstErrors | map("record \(.row) \(.kind) \(.detail)") | join("; "))' <<<"$json")
        note_fail 2 "partition $p: $summary"
        return 0
    fi
    rows=$(jq -r '.rows' <<<"$json")
    lines=$(grep -c '' "$file")
    blank=$(grep -c '^$' "$file" || true) # CSVLoader (commons-csv) ignores empty lines, and so does sort's effect on them
    if [ "$rows" != "$mrows" ]; then
        note_fail 2 "partition $p: the file holds $rows records, the split manifest says $mrows"
        return 0
    fi
    if [ "$((lines - blank))" != "$rows" ]; then
        note_fail 2 "partition $p: $((lines - blank)) non-empty lines but $rows CSV records (embedded line breaks cannot be sorted safely)"
        return 0
    fi
    jq -n --arg name "$p" --argjson ic "$json" --argjson blank "$blank" \
        '{name: $name, rows: $ic.rows, distinctRawConcepts: $ic.distinctConcepts, zeroTimestamps: $ic.zeroTimestamps,
          likelySecondsTimestamps: $ic.likelySecondsTimestamps, inputWarnings: ($ic.warnings + {blankLines: $blank})}' >"$STATE/check/$p.json"
}

run_workers() { # SUBCOMMAND : run it for every partition, PARALLEL_JVMS_EFFECTIVE at a time
    printf '%s\n' "${PARTS[@]}" | xargs -P "$PARALLEL_JVMS_EFFECTIVE" -I{} "$SELF" "$1" {}
}

# check_all_partitions : phase 1 for every selected partition, before anything is sorted or loaded
check_all_partitions() {
    local p
    run_workers __check_one || log "a check worker exited abnormally"
    for p in "${PARTS[@]}"; do
        [ -f "$STATE/check/$p.json" ] || [ -s "$STATE/failures.tsv" ] || note_fail 3 "partition $p: the input check did not finish"
    done
    stop_if_failed
}

# every selected partition must have a result record after the load
require_results() {
    local p
    for p in "${PARTS[@]}"; do
        [ -f "$STATE/result/$p.json" ] || note_fail 3 "partition $p: the load did not finish"
    done
    stop_if_failed
}

# write_split_manifest : OUT_DIR/manifest.json of a split flow, written last
write_split_manifest() {
    local m="$IN_DIR/split_manifest.json"
    slurp_dir_to "$STATE/result" "$STATE/results.json"
    jq -n \
        --arg flow "$FLOW" --arg loader "$FLOW_LOADER" --arg jarSha "$(sha256_of "$LOADER_JAR")" \
        --arg sha "$(cap_get "$CAPABILITIES_FILE" pic_sure_sha)" --arg ref "$(cap_get "$CAPABILITIES_FILE" pic_sure_ref)" \
        --arg keyMode "$KEY_MODE" --arg runId "$SPLIT_RUN_ID" --arg msha "$(sha256_of "$m")" \
        --rawfile requested <(printf '%s' "$PARTITIONS") --argjson n "$PARALLEL_JVMS_EFFECTIVE" --arg created "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --slurpfile split "$m" --slurpfile results "$STATE/results.json" '
        $results[0] as $r |
        {schema: 2, flow: $flow, createdAt: $created,
         loader: {name: $loader, jarSha256: $jarSha, parallelJvms: $n},
         picSure: {sha: $sha, ref: $ref},
         key: {mode: $keyMode, servable: ($keyMode == "secret")},
         input: {mode: "split"},
         split: {runId: (if $runId == "" then null else $runId end), manifestSha256: $msha, input: ($split[0].input // null),
                 globals: ($split[0].globals // null)},
         requestedPartitions: $requested,
         partitions: ($r | map({name, rows, observations, concepts, skippedRows, zeroTimestamps, likelySecondsTimestamps,
                                inputWarnings, attempts, files})),
         totals: {partitions: ($r | length), rows: ([$r[].rows] | add), observations: ([$r[].observations] | add)}}' \
        >"$OUT_DIR/manifest.json.tmp"
    mv "$OUT_DIR/manifest.json.tmp" "$OUT_DIR/manifest.json"
}
