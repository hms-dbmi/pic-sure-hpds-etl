#!/bin/bash
#
# Generic assertions that hold for EVERY hpds-etl JobResult report, independent of which
# job produced it. Per-job scripts run this first, then add their own metric assertions.
#
# The report is written by ReportWriter as <reports-dir>/<job>-<runId>.json.
#
# Usage:
#   validate-report.sh <report.json>
#   validate-report.sh <reports-dir> <job-name> <run-id>
#
# Exit: 0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

if [[ $# -eq 1 ]]; then
  REPORT="$1"
elif [[ $# -eq 3 ]]; then
  REPORT="$1/$2-$3.json"
else
  echo "usage: $0 <report.json> | $0 <reports-dir> <job-name> <run-id>" >&2
  exit 64
fi

echo "Validating report: $REPORT"

if [[ ! -f "$REPORT" ]]; then
  # A missing report means the JAR never got far enough to write one -- the run did not
  # merely fail, it failed before it could explain itself.
  fail "report file does not exist: $REPORT"
  summary
  exit 1
fi

check "report is valid JSON"                jq -e . "$REPORT"
check "report names its job"                jq -e '.jobName | type == "string" and length > 0' "$REPORT"
check "report carries a run id"             jq -e '.runId   | type == "string" and length > 0' "$REPORT"
check "status is SUCCESS"                   jq -e '.status == "SUCCESS"' "$REPORT"
check "exit code is a success code"         jq -e '.exitCode == "SUCCESS" or .exitCode == "SUCCESS_WITH_WARNINGS"' "$REPORT"
check "no input-validation errors"          jq -e '(.inputValidation.counts.error  // 0) == 0' "$REPORT"
check "no output-validation errors"         jq -e '(.outputValidation.counts.error // 0) == 0' "$REPORT"
check "no errorMessage recorded"            jq -e '.errorMessage == null' "$REPORT"
soft  "ran without validation warnings"     jq -e '((.inputValidation.counts.warning // 0) + (.outputValidation.counts.warning // 0)) == 0' "$REPORT"

# SUCCESS_WITH_WARNINGS also exits 0, so the process status alone cannot distinguish a
# clean run from one that logged warnings. The report can, and callers should care.
if jq -e '.exitCode == "SUCCESS_WITH_WARNINGS"' "$REPORT" >/dev/null 2>&1; then
  warn "job reported SUCCESS_WITH_WARNINGS"
fi

echo ""
echo "  Job: $(jq -r '.jobName' "$REPORT") ($(jq -r '.jobType' "$REPORT")) run $(jq -r '.runId' "$REPORT")"
echo "  Exit: $(jq -r '.exitCode' "$REPORT")   Duration: $(jq -r '.durationMillis' "$REPORT") ms"

echo ""
echo "  Metrics:"
jq -r '.metrics // {} | to_entries[] | "         \(.key) = \(.value | tostring)"' "$REPORT"

ISSUES=$(jq -r '
  [ (.inputValidation.issues // [])[], (.outputValidation.issues // [])[] ]
  | map(select(.severity == "ERROR" or .severity == "WARNING"))
  | .[] | "         [\(.severity)] \(.code): \(.message)\(if .location then " (\(.location))" else "" end)"
' "$REPORT")
if [[ -n "$ISSUES" ]]; then
  echo ""
  echo "  Errors and warnings:"
  printf '%s\n' "$ISSUES"
fi

summary
