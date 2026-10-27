#!/usr/bin/env bash
# shellcheck disable=SC2034  # NAME_REASON is read by the sourcing script
# Functions shared by the container (entrypoint.sh, flow-lib.sh, flows/*.sh), the Jenkins agent (preflight.sh) and the runner host
# (runner/host-lib.sh, scripts/stage-inputs.sh) that need no Java, so tests/run-tests.sh can exercise them on a laptop or in CI.
# Sourced, never executed. Keep it bash 3.2 compatible (macOS): no associative arrays, mapfile or ${var,,}.
# Nothing here prints a key: key files are only ever handed to grep and wc.
# The runner host sources this file too (user_data.sh.tpl defines finish() and write_status() first): define no function here whose
# name a host script uses. Container-only runtime (failure bookkeeping, report, verification) lives in flow-lib.sh.

PARTITION_NAME_RE='^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'

# Strings that mean a loader run silently went wrong even when the JVM exits 0 (see the design brief, finding 3).
LOG_FAIL_PATTERNS=(
    'CRYPTO KEY NOT FOUND'
    'Exception thrown by removal listener'
    'OutOfMemoryError'
    'NumberFormatException'
    'No space left on device'
)

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }

# valid_partition_name NAME SUFFIX_AWARE(0|1)
# Returns 0 if NAME may be a partition. Otherwise returns 1 and sets NAME_REASON.
# Hyphens are only allowed when the loader derives the partition from the whole "<name>-allConcepts.csv" suffix: an old CSVLoader
# takes the text before the first "-" and would put "tutorial-x" into a partition "tutorial".
valid_partition_name() {
    local name=$1 suffix_aware=$2
    NAME_REASON=""
    if [[ ! $name =~ $PARTITION_NAME_RE ]]; then
        NAME_REASON="does not match $PARTITION_NAME_RE"
        return 1
    fi
    case $name in
        *..*)
            NAME_REASON="contains '..'"
            return 1
            ;;
    esac
    case $name in
        *-*)
            if [ "$suffix_aware" != 1 ]; then
                NAME_REASON="contains '-' but this loader would cut the partition name at the first '-'"
                return 1
            fi
            ;;
    esac
    return 0
}

# ---------------------------------------------------------------------------------------------------------------------------
# Flows (ADR-0013): one runner, separate flow scripts under flows/. A run is one flow or a comma separated list of them; the
# flows run one after another over the same staged inputs. Each flow declares the input it reads and the loader it runs.
#   csvloader-split         CSVLoader over the splitter's <partition>-allConcepts.csv files (SPLIT_URI)
#   picsureloader-split     PICSURELoader over the same split files (SPLIT_URI)
#   picsureloader-combined  PICSURELoader over ONE combined allConcepts file, partitioned by consent by the loader (COMBINED_URI)
# ---------------------------------------------------------------------------------------------------------------------------
FLOW_NAMES="csvloader-split picsureloader-split picsureloader-combined"
FLOWS_REASON=""

# flow_known NAME
flow_known() {
    case $1 in
        csvloader-split | picsureloader-split | picsureloader-combined) return 0 ;;
        *) return 1 ;;
    esac
}

# flow_input NAME : which staged input the flow reads: split | combined
flow_input() {
    case $1 in
        csvloader-split | picsureloader-split) echo split ;;
        picsureloader-combined) echo combined ;;
        *) return 1 ;;
    esac
}

# flow_loader NAME : CSVLoader | PICSURELoader
flow_loader() {
    case $1 in
        csvloader-split) echo CSVLoader ;;
        picsureloader-split | picsureloader-combined) echo PICSURELoader ;;
        *) return 1 ;;
    esac
}

# flows_normalize LIST : the list without white space (blanks, tabs and newlines around or between names are dropped)
flows_normalize() {
    local s=${1//[[:space:]]/}
    s=${s#,}
    s=${s%,}
    printf '%s' "$s"
}

# flows_validate LIST : 0 if LIST (already normalized) is one or more known flow names without duplicates. Sets FLOWS_REASON.
flows_validate() {
    local list=$1 f seen=","
    FLOWS_REASON=""
    if [ -z "$list" ]; then
        FLOWS_REASON="names no flow (one of: $FLOW_NAMES)"
        return 1
    fi
    case $list in
        *,,*)
            FLOWS_REASON="holds an empty entry"
            return 1
            ;;
    esac
    local IFS=,
    for f in $list; do
        if ! flow_known "$f"; then
            FLOWS_REASON="names an unknown flow '$f' (one of: $FLOW_NAMES)"
            return 1
        fi
        case $seen in
            *",$f,"*)
                FLOWS_REASON="lists '$f' twice"
                return 1
                ;;
        esac
        seen="$seen$f,"
    done
    return 0
}

# flows_use_input LIST split|combined : 0 if any flow of LIST reads that input
flows_use_input() {
    local list=$1 want=$2 f
    local IFS=,
    for f in $list; do
        flow_known "$f" || continue
        [ "$(flow_input "$f")" != "$want" ] || return 0
    done
    return 1
}

# worst_exit CODE... : the highest of the exit codes. A run of several flows exits with the worst flow's code: 0 ok < 2 input
# contract < 3 load or checks failed < 4 AWS / infrastructure < 5 configuration < 6 runner setup < 124 time limit.
worst_exit() {
    local w=0 c
    for c in "$@"; do
        is_uint "$c" || { echo 4; return 0; }
        [ "$c" -le "$w" ] || w=$c
    done
    echo "$w"
}

# cap_get FILE KEY : value of KEY=value in a capabilities file (never sourced); empty if absent.
cap_get() {
    local file=$1 key=$2
    [ -r "$file" ] || return 0
    sed -n "s/^${key}=//p" "$file" | sed -n 1p
}

# key_file_ok FILE : 0 if FILE is one non-empty line of 16, 24 or 32 printable characters (the loaders use the trimmed bytes
# as an AES key). Return codes: 1 missing/unreadable, 2 not exactly one line, 3 wrong length or characters.
key_file_ok() {
    local f=$1
    [ -f "$f" ] && [ -r "$f" ] || return 1
    [ "$(grep -c '' "$f")" = 1 ] || return 2
    LC_ALL=C grep -qE '^([[:graph:]]{16}|[[:graph:]]{24}|[[:graph:]]{32})$' "$f" || return 3
    return 0
}

# key_leaked KEYFILE PATH... : 0 (true) if the key text occurs in any file under PATH. KEYFILE must pass key_file_ok first, or
# an empty line in it would match everything.
key_leaked() {
    local keyfile=$1
    shift
    grep -rF -q -f "$keyfile" -- "$@" 2>/dev/null
}

# log_problems FILE : prints each failure pattern found in FILE, one per line.
log_problems() {
    local file=$1 p
    for p in "${LOG_FAIL_PATTERNS[@]}"; do
        if grep -qF -- "$p" "$file" 2>/dev/null; then
            printf '%s\n' "$p"
        fi
    done
}

# is_uint STRING
is_uint() {
    case $1 in
        '' | *[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

# slurp_dir_to DIR OUTFILE : OUTFILE = one JSON array holding every DIR/*.json, in name order. The data goes through pipes and
# files only: with ~800 partitions the array is far over the kernel's 128 KiB limit for a single argument (MAX_ARG_STRLEN),
# so it must never be passed to jq with --argjson or built with a glob on a command line. An empty DIR gives [].
slurp_dir_to() {
    local dir=$1 out=$2
    find "$dir" -maxdepth 1 -name '*.json' -type f 2>/dev/null | LC_ALL=C sort | tr '\n' '\0' | xargs -0 cat | jq -s '.' >"$out"
}

# sha256_of FILE : hex digest (sha256sum on Linux, shasum on macOS)
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ---------------------------------------------------------------------------------------------------------------------------
# Helpers shared by preflight.sh (Jenkins agent), runner/host-lib.sh and scripts/stage-inputs.sh (runner host). They are
# here so the sizing rules and the write guards exist exactly once.
# ---------------------------------------------------------------------------------------------------------------------------

# The legacy bucket and the in-boundary mirror prefix that carries its name. Nothing in this pipeline may write there.
LEGACY_BUCKET_MARKER='avillach-73-bdcatalyst-etl'

GIB=1073741824

# s3_bucket URI / s3_key URI
s3_bucket() { local rest=${1#s3://}; echo "${rest%%/*}"; }
s3_key() { local rest=${1#s3://}; echo "${rest#*/}"; }

# is_safe_value STRING : only characters that are harmless in a shell word, a URL path and a Terraform string
is_safe_value() { [[ $1 =~ ^[A-Za-z0-9_./:@+=,~-]*$ ]]; }

# read_list FILE : the non-comment, non-blank lines of an allow-list file, trimmed
read_list() {
    local line
    [ -r "$1" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%%#*}
        line=${line#"${line%%[![:space:]]*}"}
        line=${line%"${line##*[![:space:]]}"}
        [ -z "$line" ] || printf '%s\n' "$line"
    done <"$1"
}

# s3_prefix_ok URI ALLOWED_PREFIXES(newline separated) : 0 if URI is an s3 URI strictly below one of the prefixes, without ".."
# or "//" segments and without the legacy bucket name anywhere. Sets URI_REASON when it returns 1.
s3_prefix_ok() {
    local uri=$1 allowed=$2 p key
    URI_REASON=""
    case $uri in
        s3://?*/?*) ;;
        *)
            URI_REASON="is not an s3://bucket/prefix URI"
            return 1
            ;;
    esac
    is_safe_value "$uri" || { URI_REASON="holds characters outside A-Za-z0-9_./:@+=,~-"; return 1; }
    case $uri in *"$LEGACY_BUCKET_MARKER"*) URI_REASON="names the legacy bucket $LEGACY_BUCKET_MARKER (never written from the boundary)"; return 1 ;; esac
    key=$(s3_key "$uri")
    key=${key%/} # one trailing slash is the prefix marker, not an empty segment
    case "/$key/" in
        */../* | *//* | */./*)
            URI_REASON="has an empty, '.' or '..' path segment"
            return 1
            ;;
    esac
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        case $uri in
            "$p"?*) return 0 ;;
        esac
    done <<<"$allowed"
    URI_REASON="is not below an allowed output prefix"
    return 1
}

# auto_jvms VCPU MEM_GIB PARTITIONS : how many CSVLoader JVMs (flow csvloader-split) to run at once. Each gets 75/N % of the memory,
# so no more than one per 32 GiB, one per two vCPUs, 8 at most and no more than there are partitions. PICSURELoader is always one
# multithreaded JVM and ignores this.
auto_jvms() {
    local vcpu=$1 mem=$2 parts=$3 n
    n=$((vcpu / 2))
    [ $((mem / 32)) -ge "$n" ] || n=$((mem / 32))
    [ "$n" -le 8 ] || n=8
    [ "$parts" -ge "$n" ] || n=$parts
    [ "$n" -ge 1 ] || n=1
    echo "$n"
}

# sort_buffer_for N : the sort -S value for each of N concurrent workers (together at most 15 % of the memory; the JVMs get the rest)
sort_buffer_for() {
    local pct=$((15 / $1))
    [ "$pct" -ge 2 ] || pct=2
    echo "${pct}%"
}

# flow_nvme_need_gib FLOW INPUT_BYTES MAX_PARTITION_BYTES JVMS : GiB the NVMe volume must hold for one flow, 30 GiB headroom included.
# INPUT_BYTES is the total of the selected split files (split flows) or the size of the combined file (combined flow).
#   csvloader-split         the input, plus per JVM running a sorted copy, sort temp files and the store (3x the largest partition)
#   picsureloader-split     the input, and the spooled stores of all partitions (2x the input)
#   picsureloader-combined  the input, the routed per-consent files (about 1x) and the stores (about 1x), and the loader's own spool
#                           of evicted concept partials (1x): 3x the input + 1x spool
flow_nvme_need_gib() {
    local flow=$1 total=$2 max=$3 n=$4 tg mg
    tg=$(((total + GIB - 1) / GIB))
    mg=$(((max + GIB - 1) / GIB))
    case $flow in
        csvloader-split) echo $((tg + 3 * n * mg + 30)) ;;
        picsureloader-split) echo $((tg * 3 + 30)) ;;
        picsureloader-combined) echo $((tg * 3 + tg + 30)) ;;
        *) return 1 ;;
    esac
}

# flows_nvme_need_gib FLOWS SPLIT_BYTES SPLIT_MAX_BYTES COMBINED_BYTES JVMS : the NVMe need of a run, the SUM over the selected flows.
# The flows run one after another but each keeps its output on the volume until the end of the run (it is published after the last
# flow), and a staged input is counted once per flow that reads it: an upper bound, never a shortfall.
flows_nvme_need_gib() {
    local list=$1 stotal=$2 smax=$3 cbytes=$4 n=$5 f sum=0 need
    local IFS=,
    for f in $list; do
        if [ "$(flow_input "$f")" = combined ]; then need=$(flow_nvme_need_gib "$f" "$cbytes" "$cbytes" "$n")
        else need=$(flow_nvme_need_gib "$f" "$stotal" "$smax" "$n"); fi
        sum=$((sum + need))
    done
    echo "$sum"
}

# pick_instance TABLE FLOWS SPLIT_BYTES SPLIT_MAX_BYTES COMBINED_BYTES PARTITIONS JVMS_PARAM(auto|N) MIN_MEM_GIB
# TABLE lines: type vcpu mem_gib nvme_gb (comments with #). Prints "type need_gib jvms" for the smallest type with at least
# MIN_MEM_GIB of memory whose NVMe (90 % of the nominal size is usable) holds flows_nvme_need_gib; returns 1 if none does.
pick_instance() {
    local table=$1 flows=$2 stotal=$3 smax=$4 cbytes=$5 parts=$6 jp=$7 minmem=$8 t v m g n need
    while read -r t v m g _; do
        [ -n "$t" ] || continue
        [ "$m" -ge "$minmem" ] || continue
        if [ "$jp" = auto ]; then n=$(auto_jvms "$v" "$m" "$parts"); else n=$jp; fi
        need=$(flows_nvme_need_gib "$flows" "$stotal" "$smax" "$cbytes" "$n")
        if [ $((g * 9 / 10)) -ge "$need" ]; then
            printf '%s %s %s\n' "$t" "$need" "$n"
            return 0
        fi
    done < <(read_list "$table")
    return 1
}

# combined_file_name URI : the file name (last path segment) of a COMBINED_URI, if it is a plain one. It becomes a local file name
# under /data/in/combined/ and a label in the manifest. Returns 1 for a URI that is not an object URI or has an unsafe name.
combined_file_name() {
    local key name
    case $1 in s3://?*/?*) ;; *) return 1 ;; esac
    case $1 in */) return 1 ;; esac
    key=$(s3_key "$1")
    name=${key##*/}
    [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,200}$ ]] || return 1
    case $name in *..*) return 1 ;; esac
    printf '%s' "$name"
}
