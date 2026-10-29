#!/bin/bash
#
# Plans the migration's Split AllConcepts stage: checks every study's legacy allConcepts input in
# S3 in one parallel pass, records its size, orders the studies largest first, and assigns each a
# lane:
#
#   pool   inputs under LARGE_STUDY_GB: run on a runner-pool worker, one after another
#   large  inputs of LARGE_STUDY_GB or more: each on its own instance, with a root volume sized
#          for its spool (the split job stages roughly the input size on disk)
#
# Largest first, so the longest runs start at the beginning instead of trailing the stage.
# A study whose input is missing (or whose id is not phs######) is reported, not planned.
#
# Usage: plan-studies.sh <studies.tsv> <out-dir>
#   studies.tsv  one study per line: <study_id> TAB <abbreviation> TAB <mapping_uri>
#
# Environment:
#   DATA_ROOT          required  s3://bucket/prefix holding {study_id}/legacy/allConcepts/
#   LARGE_STUDY_GB     default 5; 0 sends every study to the pool lane
#   HEAD_PARALLELISM   default 16 concurrent S3 HEAD requests
#   AWS_REGION         default us-east-1
#
# Writes:
#   <out-dir>/plan.tsv     header, then lane, study_id, abbreviation, input_bytes, input_uri,
#                          mapping_uri, volume_gb ("-" in the pool lane); largest input first
#   <out-dir>/missing.tsv  header, then study_id, abbreviation, input_uri, reason
#
# Exit: 0 planned (missing studies are listed, not fatal) | 1 inputs could not be checked
#       (an S3 error other than not-found: access, network, throttling) | 64 usage
set -uo pipefail

STUDIES="${1:-}"
OUT="${2:-}"
if [[ -z "$STUDIES" || -z "$OUT" || ! -f "$STUDIES" ]]; then
  echo "usage: $0 <studies.tsv> <out-dir>" >&2
  exit 64
fi
DATA_ROOT="${DATA_ROOT:-}"
DATA_ROOT="${DATA_ROOT%/}"
if [[ ! "$DATA_ROOT" =~ ^s3://[a-z0-9.-]+(/[A-Za-z0-9._/-]*)?$ ]]; then
  echo "plan-studies: DATA_ROOT must be an s3:// URI, got '$DATA_ROOT'" >&2
  exit 64
fi
LARGE_STUDY_GB="${LARGE_STUDY_GB:-5}"
HEAD_PARALLELISM="${HEAD_PARALLELISM:-16}"
[[ "$LARGE_STUDY_GB" =~ ^[0-9]+$ ]] || { echo "plan-studies: LARGE_STUDY_GB must be a whole number, got '$LARGE_STUDY_GB'" >&2; exit 64; }
[[ "$HEAD_PARALLELISM" =~ ^[1-9][0-9]*$ ]] || HEAD_PARALLELISM=16
export AWS_REGION="${AWS_REGION:-us-east-1}"

mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

BUCKET="${DATA_ROOT#s3://}"; BUCKET="${BUCKET%%/*}"
PREFIX="${DATA_ROOT#s3://$BUCKET}"; PREFIX="${PREFIX#/}"

# One HEAD per study, run HEAD_PARALLELISM at a time. Each writes <work>/<n>: "ok <bytes>",
# "missing <message>" or "error <message>".
head_one() {
  local n="$1" key="$2" out err
  if out=$(aws s3api head-object --bucket "$3" --key "$key" --region "$AWS_REGION" \
             --query ContentLength --output text 2>"$4/$n.err"); then
    echo "ok $out" > "$4/$n"
  else
    err=$(tr '\n' ' ' < "$4/$n.err")
    case "$err" in
      *"(404)"*|*"Not Found"*|*NoSuchKey*) echo "missing $err" > "$4/$n" ;;
      *) echo "error $err" > "$4/$n" ;;
    esac
  fi
}
export -f head_one

n=0
: > "$WORK/requests"
: > "$WORK/studies"
while IFS=$'\t' read -r sid abv mapping || [[ -n "$sid" ]]; do
  sid="${sid//$'\r'/}"; abv="${abv//$'\r'/}"; mapping="${mapping//$'\r'/}"
  [[ -z "$sid" ]] && continue
  n=$((n + 1))
  printf '%s\t%s\t%s\t%s\n' "$n" "$sid" "$abv" "$mapping" >> "$WORK/studies"
  if [[ "$sid" =~ ^phs[0-9]{6}$ ]]; then
    key="${PREFIX:+$PREFIX/}$sid/legacy/allConcepts/${sid}_allConcepts_new_search_with_data_analyzer.csv"
    printf '%s %s %s %s\n' "$n" "$key" "$BUCKET" "$WORK" >> "$WORK/requests"
  else
    echo "invalid" > "$WORK/$n"
  fi
done < "$STUDIES"

echo "plan-studies: checking $n study input(s) under $DATA_ROOT ($HEAD_PARALLELISM at a time)"
if [[ -s "$WORK/requests" ]]; then
  xargs -P "$HEAD_PARALLELISM" -L 1 bash -c 'head_one "$@"' _ < "$WORK/requests"
fi

LARGE_BYTES=$((LARGE_STUDY_GB * 1024 * 1024 * 1024))
GIB=1073741824
printf 'lane\tstudy_id\tabbreviation\tinput_bytes\tinput_uri\tmapping_uri\tvolume_gb\n' > "$OUT/plan.tsv"
printf 'study_id\tabbreviation\tinput_uri\treason\n' > "$OUT/missing.tsv"
: > "$WORK/planned"
errors=0
while IFS=$'\t' read -r i sid abv mapping; do
  input="$DATA_ROOT/$sid/legacy/allConcepts/${sid}_allConcepts_new_search_with_data_analyzer.csv"
  read -r kind detail < "$WORK/$i" 2>/dev/null || { kind=error; detail="no result recorded for the HEAD request"; }
  case "$kind" in
    ok)
      if [[ ! "$detail" =~ ^[0-9]+$ ]]; then
        echo "plan-studies: $sid: S3 returned a non-numeric size '$detail' for $input" >&2
        errors=$((errors + 1)); continue
      fi
      if (( LARGE_STUDY_GB > 0 && detail >= LARGE_BYTES )); then
        # Spool + image + headroom: twice the input, plus 50 GiB, at least 100 GiB.
        vol=$(( (detail + GIB - 1) / GIB * 2 + 50 ))
        (( vol < 100 )) && vol=100
        lane=large
      else
        lane=pool; vol=-
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$lane" "$sid" "$abv" "$detail" "$input" "$mapping" "$vol" >> "$WORK/planned"
      ;;
    missing)
      printf '%s\t%s\t%s\t%s\n' "$sid" "$abv" "$input" "legacy allConcepts input not found" >> "$OUT/missing.tsv" ;;
    invalid)
      printf '%s\t%s\t%s\t%s\n' "$sid" "$abv" "" "study id is not phs###### (6 digits)" >> "$OUT/missing.tsv" ;;
    *)
      echo "plan-studies: $sid: could not check $input: $detail" >&2
      errors=$((errors + 1)) ;;
  esac
done < "$WORK/studies"

sort -t $'\t' -k4,4nr -k2,2 "$WORK/planned" >> "$OUT/plan.tsv"

pool=$(awk -F'\t' 'NR > 1 && $1 == "pool"' "$OUT/plan.tsv" | wc -l | tr -d ' ')
large=$(awk -F'\t' 'NR > 1 && $1 == "large"' "$OUT/plan.tsv" | wc -l | tr -d ' ')
missing=$(($(wc -l < "$OUT/missing.tsv") - 1))
echo "plan-studies: $pool pool-lane, $large large-lane (>= ${LARGE_STUDY_GB} GiB), $missing without a usable input"
if (( missing > 0 )); then
  tail -n +2 "$OUT/missing.tsv" | while IFS=$'\t' read -r sid _ input reason; do
    echo "plan-studies:   $sid: $reason${input:+ ($input)}"
  done
fi
if (( errors > 0 )); then
  echo "plan-studies: $errors input(s) could not be checked (see above); not planning a partial stage" >&2
  exit 1
fi
exit 0
