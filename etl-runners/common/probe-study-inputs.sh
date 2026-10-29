#!/bin/bash
# Probes every study's permanent-pipeline inputs in S3 at once, for the orchestrator's
# "Resolve studies" stage (/Jenkinsfile, etl-runners/common/orchestrator.groovy).
#
#   probe-study-inputs.sh <study-ids-file> <out.tsv>
#
# Environment:
#   DATA_ROOT             s3://bucket/prefix holding one folder per study id
#   DECODED_DATA_DIR      folder under {DATA_ROOT}/{study_id}/ holding the decoded data CSVs
#   CONCEPT_MAPPING_FILE  file under {DATA_ROOT}/{study_id}/ holding the concept mapping
#   PROBE_PARALLELISM     studies probed at once (default 16)
#   AWS_REGION            passed to the aws cli when set
#
# One `aws s3 ls` per input per study (rawData/, the decoded data folder, the mapping), run
# PROBE_PARALLELISM studies at a time with xargs -P, instead of 2-3 sequential calls per study
# from Groovy. Writes <out.tsv> with a header and one row per study, in the input order:
#
#   study_id  sstr_candidates  csv_count  csv_bytes  mapping_found  probe_error
#
#   sstr_candidates  every *.txt name in {study}/rawData/, '/'-separated (the orchestrator picks
#                    the SSTR from them by the participants-migration rule)
#   csv_count/bytes  *.csv objects directly in the decoded data folder, and their total size
#   mapping_found    1 when the mapping object exists, else 0
#   probe_error      empty, or why S3 could not be listed (credentials, throttling, a typo in
#                    the bucket). The orchestrator fails on it: that is not a study's data problem.
#
# `aws s3 ls` exits 1 with nothing on stderr when a prefix simply holds no objects; that is "not
# found", not an error. Anything on stderr with a non-zero exit is an error.
#
# Exit: 0 written | 1 a probe could not be completed | 64 usage.
set -uo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <study-ids-file> <out.tsv>" >&2
  exit 64
fi
STUDIES_FILE="$1"
OUT="$2"
: "${DATA_ROOT:?DATA_ROOT is required}"
: "${DECODED_DATA_DIR:?DECODED_DATA_DIR is required}"
: "${CONCEPT_MAPPING_FILE:?CONCEPT_MAPPING_FILE is required}"
PROBE_PARALLELISM="${PROBE_PARALLELISM:-16}"

DATA_ROOT="${DATA_ROOT%/}"
DECODED_DATA_DIR="${DECODED_DATA_DIR#/}"; DECODED_DATA_DIR="${DECODED_DATA_DIR%/}"
CONCEPT_MAPPING_FILE="${CONCEPT_MAPPING_FILE#/}"

PROBES=$(mktemp -d "${TMPDIR:-/tmp}/probe-study-inputs.XXXXXX")
trap 'rm -rf "$PROBES"' EXIT

# s3_ls <uri> <err-file>: the listing on stdout. Returns 0 (listed, possibly empty) or 3 (error,
# the reason in <err-file>).
s3_ls() {
  local out rc
  out=$(aws s3 ls "$1" ${AWS_REGION:+--region "$AWS_REGION"} 2>"$2"); rc=$?
  if [ "$rc" -ne 0 ] && [ -s "$2" ]; then
    return 3
  fi
  : > "$2"
  printf '%s\n' "$out"
}

# The object names of an `aws s3 ls` listing (not PRE folders): everything after the date, time
# and size columns.
names() {
  awk '$1 != "PRE" && NF >= 4 { n = $0; sub(/^[^ ]+ +[^ ]+ +[0-9]+ /, "", n); print n }'
}

probe_study() {
  local n="$1" sid base listing err sstr csvs count bytes mapping_uri mapping_name mapping_found=0 why=""
  sid=$(sed -n "${n}p" "$STUDIES_FILE" | tr -d '\r[:space:]')
  base="$DATA_ROOT/$sid"
  err="$PROBES/$n.err"

  if listing=$(s3_ls "$base/rawData/" "$err"); then
    sstr=$(names <<<"$listing" | grep -i '\.txt$' | paste -sd/ - || true)
  else
    why="rawData/: $(head -c 300 "$err")"
  fi

  if [ -z "$why" ] && listing=$(s3_ls "$base/$DECODED_DATA_DIR/" "$err"); then
    csvs=$(awk '$1 != "PRE" && NF >= 4 && tolower($NF) ~ /\.csv$/ { c++; b += $3 } END { printf "%d\t%.0f", c, b }' <<<"$listing")
    count="${csvs%%$'\t'*}"; bytes="${csvs##*$'\t'}"
  elif [ -z "$why" ]; then
    why="$DECODED_DATA_DIR/: $(head -c 300 "$err")"
  fi

  mapping_uri="$base/$CONCEPT_MAPPING_FILE"
  mapping_name="${mapping_uri##*/}"
  if [ -z "$why" ] && listing=$(s3_ls "$mapping_uri" "$err"); then
    # ls on a key lists every key it prefixes (mapping2.csv.bak too): only an exact name counts.
    if names <<<"$listing" | grep -qxF -- "$mapping_name"; then
      mapping_found=1
    fi
  elif [ -z "$why" ]; then
    why="$CONCEPT_MAPPING_FILE: $(head -c 300 "$err")"
  fi

  why=$(printf '%s' "$why" | tr '\t\r\n' '   ')
  # Written whole, then renamed: a probe that dies midway leaves no row, which the merge below
  # reports as an incomplete probe instead of a study with no inputs.
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$sid" "${sstr:-}" "${count:-0}" "${bytes:-0}" "$mapping_found" "$why" \
    > "$PROBES/$n.tmp" && mv "$PROBES/$n.tmp" "$PROBES/$n"
}
export -f probe_study s3_ls names
export STUDIES_FILE PROBES DATA_ROOT DECODED_DATA_DIR CONCEPT_MAPPING_FILE
[ -n "${AWS_REGION:-}" ] && export AWS_REGION

total=$(grep -c . "$STUDIES_FILE" || true)
if [ "$total" -gt 0 ]; then
  seq 1 "$total" | xargs -P "$PROBE_PARALLELISM" -I{} bash -c 'probe_study "$1"' _ {}
fi

{
  printf 'study_id\tsstr_candidates\tcsv_count\tcsv_bytes\tmapping_found\tprobe_error\n'
  for ((n = 1; n <= total; n++)); do
    if [ ! -f "$PROBES/$n" ]; then
      echo "probe-study-inputs: study #$n ($(sed -n "${n}p" "$STUDIES_FILE")) was not probed" >&2
      exit 1
    fi
    cat "$PROBES/$n"
  done
} > "$OUT.partial" || exit 1
mv "$OUT.partial" "$OUT"
echo "Probed $total study/studies' inputs under $DATA_ROOT ($PROBE_PARALLELISM at a time)."
