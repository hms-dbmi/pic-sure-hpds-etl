#!/bin/bash
#
# Post-run validation for merge-allconcepts, over the artifacts fetched from the runner's S3
# report prefix:
#   merge-allconcepts-<runId>.json           the JobResult
#   merge-allconcepts-<runId>-folders.json   every folder's outcome; only written when the run failed
#
# Runs the standard report checks, then asserts that the merge metrics agree with each other and
# with the per-folder actions.
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

REPORT="$REPORTS/merge-allconcepts-$RUN_ID.json"
FOLDERS="$REPORTS/merge-allconcepts-$RUN_ID-folders.json"

echo "=========================================================="
echo " merge-allconcepts validation (run $RUN_ID)"
echo "=========================================================="

"$HERE/../common/validate-report.sh" "$REPORT"
case $? in
  0)  echo "  [ok]   standard report checks"; _PASS=$((_PASS + 1)) ;;
  10) echo "  [warn] standard report checks (warnings above)"; _WARN=$((_WARN + 1)) ;;
  *)  echo "  [FAIL] standard report checks"; _FAIL=$((_FAIL + 1)) ;;
esac

if [[ -f "$FOLDERS" ]]; then
  # A failed run: its report has no metrics, so show what each folder did.
  note "the run failed; per-folder outcomes from $(basename "$FOLDERS"):"
  jq -r '(.actions // {}) | to_entries[]
         | "         \(.key): \(.value.action)\(if .value.error then " -- \(.value.error)" else "" end)"' "$FOLDERS"
  fail "$(jq -r '.foldersFailed' "$FOLDERS") of $(jq -r '.consentFoldersDiscovered' "$FOLDERS") consent folder(s) failed"
fi

if [[ ! -f "$REPORT" ]] || [[ "$(jq -r '.metrics.consentFoldersDiscovered // "absent"' "$REPORT")" == "absent" ]]; then
  summary
  exit 1
fi

m() { jq -r ".metrics.$1 // 0" "$REPORT"; }
# Number of folders in .metrics.actions whose action is one of the given names.
actions() {
  jq -r --arg names "$*" \
    '($names | split(" ")) as $n | [(.metrics.actions // {})[] | select(.action as $a | $n | any(. == $a))] | length' \
    "$REPORT"
}

DISCOVERED=$(m consentFoldersDiscovered)
MERGED=$(m foldersMerged)
SKIPPED=$(m foldersSkipped)
REMOVED=$(m foldersRemoved)
FILES=$(m totalFilesMerged)
LISTED=$(jq -r '(.metrics.actions // {}) | length' "$REPORT")

note "studies checked: $(m studiesChecked); consent folders: $DISCOVERED"
note "merged: $MERGED ($FILES source file(s)); up to date: $SKIPPED; MERGED removed: $REMOVED"
echo "  Per-folder actions:"
jq -r '(.metrics.actions // {}) | to_entries[] | "         \(.key): \(.value.action) (\(.value.sourceFileCount) source(s))"' "$REPORT"

# Every discovered folder has exactly one action, so the per-action counts sum to the total.
check "one action per consent folder ($LISTED listed, $DISCOVERED discovered)" test "$LISTED" -eq "$DISCOVERED"
check "foldersMerged ($MERGED) = MISSING + NO_FINGERPRINT + SOURCES_CHANGED actions" \
  test "$(actions MISSING NO_FINGERPRINT SOURCES_CHANGED)" -eq "$MERGED"
check "foldersSkipped ($SKIPPED) = UP_TO_DATE actions" test "$(actions UP_TO_DATE)" -eq "$SKIPPED"
check "foldersRemoved ($REMOVED) = REMOVED_NO_SOURCES actions" test "$(actions REMOVED_NO_SOURCES)" -eq "$REMOVED"
check "no FAILED action in a finished run" test "$(actions FAILED)" -eq 0
# Every merged folder has at least one source file.
check "totalFilesMerged ($FILES) >= foldersMerged ($MERGED)" test "$FILES" -ge "$MERGED"

summary
