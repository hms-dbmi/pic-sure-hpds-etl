#!/usr/bin/env bash
# Stages the inputs of one hpds-pheno-load run on the runner host, onto the NVMe volume. The container later mounts the directory
# read-only. Runs on the runner (shipped by `make upload-code`); the tests run it against a directory standing in for S3.
#   SPLIT_URI     split_manifest.json plus the selected <partition>-allConcepts.csv files of a split-allconcepts-by-consent output
#                 prefix, into <dest-dir>/                                                  (flows csvloader-split, picsureloader-split)
#   COMBINED_URI  ONE object, the combined allConcepts file, into <dest-dir>/combined/<name>   (flow picsureloader-combined)
# Either or both; at least one.
#
# Usage: stage-inputs.sh <dest-dir> <stage-json>
#   <dest-dir>    receives the inputs (created; must be empty)
#   <stage-json>  receives the record of what was staged (eTags, sizes; schema 2: "split" and "combined", each an object or null)
# Environment: SPLIT_URI (s3://bucket/prefix/), COMBINED_URI (s3://bucket/key/to/file), PARTITIONS (comma/space separated allow-list
#              of the split input; empty = every partition), AWS_REGION (us-east-1), STAGE_PARALLEL (downloads at once, 8)
#
# Exit codes: 0 staged | 2 an input breaks the contract (global partition, bad name or file, a selected file is missing or empty, the
# combined object is missing or empty) | 4 AWS or consistency error (a download failed, an object changed while it was read, a size
# differs) | 5 configuration (bad URI or PARTITIONS, a requested partition is not in the manifest, no input named).
#
# Provenance: the split manifest's eTag (and version id) is recorded and re-read after the download; the partition files are listed
# before and after and must not have changed (key, size, eTag), and every downloaded size must match the listing. The combined object
# is pinned the same way: eTag, version and size are read before the download, the downloaded size must equal the listed size, and
# the object is listed and head-checked again afterwards (key, size, eTag unchanged).
# Never prints object contents. Needs aws, jq.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# lib.sh sits next to scripts/ in the repository and in the code prefix
# shellcheck source=../lib.sh
. "$HERE/../lib.sh"

DEST=${1:-}
STAGE_JSON=${2:-}
SPLIT_URI=${SPLIT_URI:-}
COMBINED_URI=${COMBINED_URI:-}
REGION=${AWS_REGION:-us-east-1}
PARALLEL=${STAGE_PARALLEL:-8}
PARTITIONS=${PARTITIONS:-}

fail() { # CODE MESSAGE
    log "stage-inputs: FAIL($1): $2"
    exit "$1"
}

[ -n "$DEST" ] && [ -n "$STAGE_JSON" ] || fail 5 "usage: stage-inputs.sh <dest-dir> <stage-json> (SPLIT_URI and/or COMBINED_URI in the environment)"
[ -n "$SPLIT_URI" ] || [ -n "$COMBINED_URI" ] || fail 5 "neither SPLIT_URI nor COMBINED_URI is set: nothing to stage"
if [ -n "$SPLIT_URI" ]; then
    case $SPLIT_URI in s3://?*/?*) ;; *) fail 5 "SPLIT_URI '$SPLIT_URI' is not an s3://bucket/prefix/ URI" ;; esac
    is_safe_value "$SPLIT_URI" || fail 5 "SPLIT_URI holds characters outside A-Za-z0-9_./:@+=,~-"
    case $SPLIT_URI in */) ;; *) SPLIT_URI="$SPLIT_URI/" ;; esac
fi
if [ -n "$COMBINED_URI" ]; then
    is_safe_value "$COMBINED_URI" || fail 5 "COMBINED_URI holds characters outside A-Za-z0-9_./:@+=,~-"
    COMBINED_NAME=$(combined_file_name "$COMBINED_URI") || fail 5 "COMBINED_URI '$COMBINED_URI' is not s3://bucket/path/<file> with a plain file name (letters, digits, '.', '_', '-')"
fi
is_uint "$PARALLEL" && [ "$PARALLEL" -ge 1 ] && [ "$PARALLEL" -le 64 ] || fail 5 "STAGE_PARALLEL must be 1..64"

mkdir -p "$DEST"
[ -z "$(find "$DEST" -mindepth 1 -maxdepth 1 -not -name lost+found -print -quit)" ] || fail 5 "$DEST is not empty"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo null >"$TMP/split.json"
echo null >"$TMP/combined.json"

# ===========================================================================================================================
# The split input
# ===========================================================================================================================
stage_split() {
    local BUCKET PREFIX MKEY M_ETAG M_VERSION M_SIZE M_ETAG2 M M_SHAPE globals name kind rows file w size got SELECTED TOTAL MAXB
    BUCKET=$(s3_bucket "$SPLIT_URI")
    PREFIX=$(s3_key "$SPLIT_URI")
    MKEY="${PREFIX}split_manifest.json"

    # --- the manifest, pinned by its eTag ----------------------------------------------------------------------------------
    head_manifest() { # prints: eTag TAB versionId TAB size
        aws s3api head-object --bucket "$BUCKET" --key "$MKEY" --region "$REGION" \
            --query '[ETag,VersionId,ContentLength]' --output text 2>"$TMP/head.err" | tr -d '"'
    }
    head_manifest >"$TMP/head1" || fail 4 "cannot read s3://$BUCKET/$MKEY ($(head -c 200 "$TMP/head.err" | tr '\n' ' '))"
    IFS=$'\t' read -r M_ETAG M_VERSION M_SIZE <"$TMP/head1"
    [ -n "$M_ETAG" ] || fail 4 "s3://$BUCKET/$MKEY has no eTag"
    aws s3 cp "s3://$BUCKET/$MKEY" "$DEST/split_manifest.json" --region "$REGION" --no-progress >"$TMP/cp.out" 2>&1 \
        || fail 4 "cannot download split_manifest.json: $(tail -n 2 "$TMP/cp.out" | tr '\n' ' ')"
    head_manifest >"$TMP/head2" || fail 4 "cannot re-read s3://$BUCKET/$MKEY"
    IFS=$'\t' read -r M_ETAG2 _ _ <"$TMP/head2"
    [ "$M_ETAG" = "$M_ETAG2" ] || fail 4 "split_manifest.json changed while it was read (eTag $M_ETAG then $M_ETAG2)"

    M="$DEST/split_manifest.json"
    jq -e . "$M" >/dev/null 2>&1 || fail 2 "split_manifest.json is not valid JSON"
    M_SHAPE=$(jq -r '
        if (.partitions | type) != "array" or (.partitions | length) == 0 then "partitions must be a non-empty array"
        elif ([.partitions[] | select((.name | type) != "string" or (.name | length) == 0 or (.name | test("[\t\n\r]"))
                                  or (.kind | type) != "string" or (.file | type) != "string" or (.file | length) == 0
                                  or (.rows | type) != "number")] | length) > 0
        then "every partition needs a string name, kind and file and a numeric rows"
        else "ok" end' "$M")
    [ "$M_SHAPE" = ok ] || fail 2 "split_manifest.json: $M_SHAPE"
    globals=$(jq -r '[.partitions[] | select(.kind == "global") | .name] | join(",")' "$M")
    [ -z "$globals" ] || fail 2 "split_manifest.json lists global partition(s) ($globals): split with globals=by-patient"

    # --- selection ---------------------------------------------------------------------------------------------------------
    jq -r '.partitions[] | [.name, .kind, (.rows | tostring), .file] | @tsv' "$M" >"$TMP/all.tsv"
    : >"$TMP/want"
    if [ -n "${PARTITIONS//[[:space:]]/}" ]; then
        printf '%s' "$PARTITIONS" | tr ', ' '\n\n' | grep -v '^$' >"$TMP/want" || true
        [ -s "$TMP/want" ] || fail 5 "PARTITIONS is set ('$PARTITIONS') but names no partition"
        if [ "$(LC_ALL=C sort "$TMP/want" | uniq -d | head -n 1)" != "" ]; then fail 5 "PARTITIONS lists a partition twice"; fi
    fi
    : >"$TMP/sel.tsv"
    while IFS=$'\t' read -r name kind rows file; do
        if [ -s "$TMP/want" ] && ! grep -qxF -- "$name" "$TMP/want"; then continue; fi
        [ "$kind" = study ] || fail 2 "partition $name has kind '$kind'; only 'study' partitions are loaded"
        # the name becomes a local file name and an S3 key: the strictest rule (the container applies the loader-specific one)
        [[ $name =~ $PARTITION_NAME_RE ]] && [[ $name != *..* ]] || fail 2 "partition name '$name' is refused (does not match $PARTITION_NAME_RE or holds '..')"
        [ "$file" = "$name-allConcepts.csv" ] || fail 2 "partition $name: file '$file' must be '$name-allConcepts.csv'"
        [ "$rows" -gt 0 ] 2>/dev/null || fail 2 "partition $name: the split manifest lists no rows"
        printf '%s\t%s\n' "$name" "$file" >>"$TMP/sel.tsv"
    done <"$TMP/all.tsv"
    if [ -s "$TMP/want" ]; then
        cut -f1 "$TMP/sel.tsv" >"$TMP/sel.names"   # never pipe cut into a quiet grep under pipefail: SIGPIPE would count a present name as missing
        while IFS= read -r w; do
            grep -qxF -- "$w" "$TMP/sel.names" || fail 5 "PARTITIONS names '$w', which is not a partition in split_manifest.json"
        done <"$TMP/want"
    fi
    [ -s "$TMP/sel.tsv" ] || fail 5 "no partitions selected"
    SELECTED=$(grep -c '' "$TMP/sel.tsv")

    # --- listing: key, size, eTag of the selected files, before and after the download -------------------------------------------
    list_selected() { # OUTFILE : sorted "file TAB size TAB eTag" for the selected files
        local out=$1
        aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$PREFIX" --region "$REGION" \
            --query 'Contents[].[Key,Size,ETag]' --output text 2>"$TMP/list.err" | tr -d '"' >"$TMP/list.raw" \
            || return 1
        # only the selected files, keyed by file name (the prefix is stripped; sub-prefixes never match)
        awk -F'\t' -v p="$PREFIX" 'NR == FNR {want[$2] = 1; next} index($1, p) == 1 && (substr($1, length(p) + 1) in want) {print substr($1, length(p) + 1) "\t" $2 "\t" $3}' \
            "$TMP/sel.tsv" "$TMP/list.raw" | LC_ALL=C sort >"$out"
    }
    list_selected "$TMP/list1" || fail 4 "cannot list s3://$BUCKET/$PREFIX ($(head -c 200 "$TMP/list.err" | tr '\n' ' '))"
    while IFS=$'\t' read -r name file; do
        size=$(awk -F'\t' -v f="$file" '$1 == f {print $2}' "$TMP/list1")
        [ -n "$size" ] || fail 2 "partition $name: $file is not under $SPLIT_URI"
        [ "$size" -gt 0 ] || fail 2 "partition $name: $file is empty in S3"
    done <"$TMP/sel.tsv"
    TOTAL=$(awk -F'\t' '{s += $2} END {printf "%d", s}' "$TMP/list1")
    MAXB=$(awk -F'\t' '$2 > m {m = $2} END {printf "%d", m}' "$TMP/list1")
    log "staging $SELECTED partition(s), $TOTAL bytes (largest $MAXB), from $SPLIT_URI"

    # --- download ----------------------------------------------------------------------------------------------------------
    export BUCKET PREFIX DEST REGION
    cut -f2 "$TMP/sel.tsv" | xargs -P "$PARALLEL" -I{} bash -c \
        'aws s3 cp "s3://$BUCKET/${PREFIX}$1" "$DEST/$1" --region "$REGION" --no-progress --only-show-errors || { echo "download failed: $1" >&2; exit 1; }' _ {} \
        || fail 4 "a partition download failed (see the lines above)"

    while IFS=$'\t' read -r file size _; do
        [ -f "$DEST/$file" ] || fail 4 "$file was not downloaded"
        got=$(wc -c <"$DEST/$file" | tr -d ' ')
        [ "$got" = "$size" ] || fail 4 "$file: downloaded $got bytes, S3 lists $size"
    done <"$TMP/list1"
    list_selected "$TMP/list2" || fail 4 "cannot list s3://$BUCKET/$PREFIX again"
    cmp -s "$TMP/list1" "$TMP/list2" || fail 4 "the split output changed while it was staged (key, size or eTag differs between the two listings)"

    # --- record ------------------------------------------------------------------------------------------------------------
    jq -R -s 'split("\n") | map(select(length > 0) | split("\t") | {file: .[0], bytes: (.[1] | tonumber), eTag: .[2]})' "$TMP/list1" >"$TMP/files.json"
    jq -n --arg uri "$SPLIT_URI" --arg etag "$M_ETAG" --arg ver "$M_VERSION" --argjson msize "${M_SIZE:-0}" \
        --argjson total "$TOTAL" --argjson max "$MAXB" --argjson n "$SELECTED" \
        --arg requested "$PARTITIONS" --slurpfile files "$TMP/files.json" --slurpfile split "$M" '
        {uri: $uri,
         manifest: {eTag: $etag, versionId: (if $ver == "None" or $ver == "" then null else $ver end), bytes: $msize,
                    partitionsInManifest: ($split[0].partitions | length), input: ($split[0].input // null)},
         requestedPartitions: $requested, selectedPartitions: $n, totalBytes: $total, largestPartitionBytes: $max, files: $files[0]}' \
        >"$TMP/split.json"
    log "staged $SELECTED partition(s); manifest eTag $M_ETAG"
}

# ===========================================================================================================================
# The combined input: one object, pinned by eTag and size
# ===========================================================================================================================
stage_combined() {
    local BUCKET KEY C_ETAG C_VERSION C_SIZE C_ETAG2 C_VERSION2 C_SIZE2 L_SIZE L_ETAG got
    BUCKET=$(s3_bucket "$COMBINED_URI")
    KEY=$(s3_key "$COMBINED_URI")

    head_combined() { # prints: eTag TAB versionId TAB size
        aws s3api head-object --bucket "$BUCKET" --key "$KEY" --region "$REGION" \
            --query '[ETag,VersionId,ContentLength]' --output text 2>"$TMP/chead.err" | tr -d '"'
    }
    list_combined() { # OUTFILE : "size TAB eTag" of exactly this key (other keys with the same prefix are ignored)
        aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$KEY" --region "$REGION" \
            --query 'Contents[].[Key,Size,ETag]' --output text 2>"$TMP/clist.err" | tr -d '"' >"$TMP/clist.raw" || return 1
        awk -F'\t' -v k="$KEY" '$1 == k {print $2 "\t" $3}' "$TMP/clist.raw" >"$1"
    }

    head_combined >"$TMP/chead1" || fail 4 "cannot read s3://$BUCKET/$KEY ($(head -c 200 "$TMP/chead.err" | tr '\n' ' '))"
    IFS=$'\t' read -r C_ETAG C_VERSION C_SIZE <"$TMP/chead1"
    [ -n "$C_ETAG" ] || fail 4 "s3://$BUCKET/$KEY has no eTag"
    list_combined "$TMP/clist1" || fail 4 "cannot list s3://$BUCKET/$KEY ($(head -c 200 "$TMP/clist.err" | tr '\n' ' '))"
    [ -s "$TMP/clist1" ] || fail 2 "the combined input $COMBINED_URI is not in S3"
    IFS=$'\t' read -r L_SIZE L_ETAG <"$TMP/clist1"
    [ "${C_SIZE:-0}" -gt 0 ] 2>/dev/null || fail 2 "the combined input $COMBINED_URI is empty in S3"
    [ "$C_SIZE" = "$L_SIZE" ] && [ "$C_ETAG" = "$L_ETAG" ] || fail 4 "the combined input changed while it was read (head: size $C_SIZE eTag $C_ETAG; listing: size $L_SIZE eTag $L_ETAG)"
    log "staging the combined input $COMBINED_NAME, $C_SIZE bytes, from $COMBINED_URI"

    mkdir -p "$DEST/combined"
    aws s3 cp "$COMBINED_URI" "$DEST/combined/$COMBINED_NAME" --region "$REGION" --no-progress --only-show-errors >"$TMP/ccp.out" 2>&1 \
        || fail 4 "cannot download the combined input: $(tail -n 2 "$TMP/ccp.out" | tr '\n' ' ')"
    [ -f "$DEST/combined/$COMBINED_NAME" ] || fail 4 "the combined input was not downloaded"
    got=$(wc -c <"$DEST/combined/$COMBINED_NAME" | tr -d ' ')
    [ "$got" = "$C_SIZE" ] || fail 4 "the combined input: downloaded $got bytes, S3 lists $C_SIZE"

    # re-listed and re-read after the download: the object must be the one that was pinned
    head_combined >"$TMP/chead2" || fail 4 "cannot re-read s3://$BUCKET/$KEY"
    IFS=$'\t' read -r C_ETAG2 C_VERSION2 C_SIZE2 <"$TMP/chead2"
    [ "$C_ETAG" = "$C_ETAG2" ] && [ "$C_SIZE" = "$C_SIZE2" ] && [ "$C_VERSION" = "$C_VERSION2" ] \
        || fail 4 "the combined input changed while it was staged (eTag $C_ETAG then $C_ETAG2, size $C_SIZE then $C_SIZE2)"
    list_combined "$TMP/clist2" || fail 4 "cannot list s3://$BUCKET/$KEY again"
    cmp -s "$TMP/clist1" "$TMP/clist2" || fail 4 "the combined input changed while it was staged (size or eTag differs between the two listings)"

    chmod 755 "$DEST/combined"
    chmod 644 "$DEST/combined/$COMBINED_NAME"
    jq -n --arg uri "$COMBINED_URI" --arg file "$COMBINED_NAME" --arg etag "$C_ETAG" --arg ver "$C_VERSION" --argjson size "$C_SIZE" \
        '{uri: $uri, file: $file, stagedAs: ("combined/" + $file), eTag: $etag,
          versionId: (if $ver == "None" or $ver == "" then null else $ver end), bytes: $size}' >"$TMP/combined.json"
    log "staged the combined input; eTag $C_ETAG"
}

if [ -n "$SPLIT_URI" ]; then stage_split; fi
if [ -n "$COMBINED_URI" ]; then stage_combined; fi

chmod 755 "$DEST"
find "$DEST" -type f -exec chmod 644 {} +

jq -n --arg staged "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --slurpfile split "$TMP/split.json" --slurpfile combined "$TMP/combined.json" \
    '{schema: 2, stagedAt: $staged, split: $split[0], combined: $combined[0]}' >"$STAGE_JSON"
