#!/bin/bash
#
# Post-run validation for generate-allconcepts-manifest, over the artifacts fetched from the
# runner's S3 report prefix:
#   generate-allconcepts-manifest-<runId>.json   the JobResult
#   allConcepts_manifest.csv                     copy of the manifest written (only when written)
#
# The job itself refuses to write a manifest with a missing or stale MERGED file; this checks
# that the copy it archived is well-formed and agrees with the report.
#
# Usage: validate.sh <reports-dir> <run-id>
# Exit:  0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REPORTS="${1:-}"
RUN_ID="${2:-}"
if [[ -z "$REPORTS" || -z "$RUN_ID" ]]; then
  echo "usage: $0 <reports-dir> <run-id>" >&2
  exit 64
fi

REPORT="$REPORTS/generate-allconcepts-manifest-$RUN_ID.json"
MANIFEST="$REPORTS/allConcepts_manifest.csv"

echo "=========================================================="
echo " generate-allconcepts-manifest validation (run $RUN_ID)"
echo "=========================================================="

"$HERE/../common/validate-report.sh" "$REPORT"
case $? in
  0)  echo "  [ok]   standard report checks"; _PASS=$((_PASS + 1)) ;;
  10) echo "  [warn] standard report checks (warnings above)"; _WARN=$((_WARN + 1)) ;;
  *)  echo "  [FAIL] standard report checks"; _FAIL=$((_FAIL + 1)) ;;
esac

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

WRITTEN=$(jq -r '.metrics.manifestWritten // false' "$REPORT")
ROWS=$(jq -r '.metrics.manifestRows // 0' "$REPORT")
check "manifest was written" test "$WRITTEN" = "true"
if [[ "$WRITTEN" != "true" ]]; then
  note "not written; every problem found:"
  jq -r '(.outputValidation.issues // [])[] | select(.severity == "ERROR") | "         \(.code): \(.message)"' "$REPORT"
  summary
  exit 1
fi
note "$(jq -r '.metrics.manifestUri' "$REPORT") version $(jq -r '.metrics.manifestVersionId' "$REPORT")"
check "manifest has a version id" test -n "$(jq -r '.metrics.manifestVersionId // ""' "$REPORT")"

check "manifest copy archived" test -f "$MANIFEST"
if [[ -f "$MANIFEST" ]]; then
  check "header is partition,s3_uri,version_id,last_updated,md5" \
    test "$(head -1 "$MANIFEST")" = "partition,s3_uri,version_id,last_updated,md5"
  check "row count matches the report ($ROWS)" test "$(($(wc -l < "$MANIFEST") - 1))" -eq "$ROWS"
  # The global AllConcepts row (partition "global") is always first; the rest are consent groups.
  check "first row is the global AllConcepts file" test "$(sed -n 2p "$MANIFEST" | cut -d, -f1)" = "global"
  check "exactly one global row" test "$(tail -n +2 "$MANIFEST" | cut -d, -f1 | grep -c '^global$' || true)" -eq 1
  bad_partition=$(tail -n +3 "$MANIFEST" | cut -d, -f1 | grep -vc '^[^.,]\{1,\}\.c[^.,]\{1,\}$' || true)
  check "every other partition is {study_id}.c{code}" test "$bad_partition" -eq 0
  check "at least one consent group listed" test "$ROWS" -gt 1
  # Values are unquoted unless they contain a comma; ids, URIs and hashes never do.
  # length() rather than a {32} interval: older mawk builds don't support intervals.
  bad_md5=$(tail -n +2 "$MANIFEST" | awk -F, 'length($5) != 32 || $5 !~ /^[0-9a-f]+$/' | grep -c . || true)
  check "every md5 is 32 hex characters" test "$bad_md5" -eq 0
  blank=$(tail -n +2 "$MANIFEST" | awk -F, '$1=="" || $2=="" || $3=="" || $4==""' | grep -c . || true)
  check "no blank fields" test "$blank" -eq 0
  dupes=$(tail -n +2 "$MANIFEST" | cut -d, -f1 | sort | uniq -d | grep -c . || true)
  check "one row per partition" test "$dupes" -eq 0
  not_s3=$(tail -n +2 "$MANIFEST" | cut -d, -f2 | grep -vc '^s3://' || true)
  check "every s3_uri is an s3:// URI" test "$not_s3" -eq 0
fi

summary
