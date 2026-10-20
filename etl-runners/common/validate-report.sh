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
  fail "report file does not exist: $REPORT (the job writes it as it finishes, so the job stopped early: timeout, crash or lost instance; its exit code and runner log say why)"
  summary
  exit 1
fi

# One jq pass per report: the migration validates ~600 sub-reports, and the ~20 jq processes
# per report this used to start made validation take longer than the migration itself.
# Every output line carries a tag, so text from the report can never be read as a check:
#   C<TAB>0|1<TAB>label   a check (1 passed)
#   W<TAB>text            a warning: the report is clean but the build is UNSTABLE (exit 10)
#   T<TAB>text            a line printed as-is (a multi-line issue message is one T line per line)
if ! LINES=$(jq -r '
  def ok(f): if (try f catch false) then "1" else "0" end;
  def chk($name; f): "C\t\(ok(f))\t\($name)";
  def txt: tostring | if . == "" then "T\t" else (split("\n")[] | "T\t" + .) end;
  chk("report names its job";        .jobName | type == "string" and length > 0),
  chk("report carries a run id";     .runId   | type == "string" and length > 0),
  chk("status is SUCCESS";           .status == "SUCCESS"),
  chk("exit code is a success code"; .exitCode == "SUCCESS" or .exitCode == "SUCCESS_WITH_WARNINGS"),
  chk("no input-validation errors";  (.inputValidation.counts.error  // 0) == 0),
  chk("no output-validation errors"; (.outputValidation.counts.error // 0) == 0),
  chk("no errorMessage recorded";    .errorMessage == null),
  ((.inputValidation.counts.warning // 0) + (.outputValidation.counts.warning // 0)
    | select(. != 0) | "W\tvalidation warnings: \(.) (see details below)"),
  (select(.exitCode == "SUCCESS_WITH_WARNINGS") | "W\tjob reported SUCCESS_WITH_WARNINGS"),
  ("",
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
  | txt)
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
    W$'\t'*)
      warn "${line#W$'\t'}" ;;
    T$'\t'*)
      printf '%s\n' "${line#T$'\t'}" ;;
    *)
      # Every line jq prints is tagged; anything else means this script and its filter disagree.
      fail "unexpected validator output: $line" ;;
  esac
done <<<"$LINES"

summary
