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

# One jq pass per report: the migration validates ~600 sub-reports, and the ~20 jq processes
# per report this used to start made validation take longer than the migration itself.
# Each output line is tagged: C<TAB>0|1<TAB>label is a check, N<TAB>text a note, anything else
# is printed as-is (an issue message can span several lines).
if ! LINES=$(jq -r '
  def ok(f): if (try f catch false) then "1" else "0" end;
  def chk($name; f): "C\t\(ok(f))\t\($name)";
  chk("report names its job";        .jobName | type == "string" and length > 0),
  chk("report carries a run id";     .runId   | type == "string" and length > 0),
  chk("status is SUCCESS";           .status == "SUCCESS"),
  chk("exit code is a success code"; .exitCode == "SUCCESS" or .exitCode == "SUCCESS_WITH_WARNINGS"),
  chk("no input-validation errors";  (.inputValidation.counts.error  // 0) == 0),
  chk("no output-validation errors"; (.outputValidation.counts.error // 0) == 0),
  chk("no errorMessage recorded";    .errorMessage == null),
  ((.inputValidation.counts.warning // 0) + (.outputValidation.counts.warning // 0)
    | select(. != 0) | "N\tvalidation warnings: \(.) (see details below)"),
  (select(.exitCode == "SUCCESS_WITH_WARNINGS") | "N\tjob reported SUCCESS_WITH_WARNINGS"),
  "",
  "  Job: \(.jobName) (\(.jobType)) run \(.runId)",
  "  Exit: \(.exitCode)   Duration: \(.durationMillis) ms",
  "",
  "  Metrics:",
  (.metrics // {} | to_entries[] | "         \(.key) = \(.value | tostring)"),
  ([ (.inputValidation.issues // [])[], (.outputValidation.issues // [])[] ]
    | map(select(.severity == "ERROR" or .severity == "WARNING"))
    | if length == 0 then empty else
        "", "  Errors and warnings:",
        (.[] | "         [\(.severity)] \(.code): \(.message)\(if .location then " (\(.location))" else "" end)")
      end)
' "$REPORT" 2>/dev/null) || [[ -z "$LINES" ]]; then
  # jq prints nothing at all for an empty file, which must not pass as a valid report.
  fail "report is valid JSON"
  summary
  exit 1
fi

check "report is valid JSON" true
while IFS= read -r line; do
  case "$line" in
    C$'\t'*)
      line="${line#C$'\t'}"
      check "${line#*$'\t'}" test "${line%%$'\t'*}" = 1 ;;
    N$'\t'*)
      note "${line#N$'\t'}" ;;
    *)
      printf '%s\n' "$line" ;;
  esac
done <<<"$LINES"

summary
