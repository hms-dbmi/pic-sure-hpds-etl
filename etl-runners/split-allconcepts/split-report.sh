#!/bin/bash
#
# Consolidates the migration's Split AllConcepts stage into one report: every study's
# study-result.json (record-study-result.sh) under <results-dir>, however the study ran.
#
# Writes, in <out-dir>:
#   split-allconcepts-report-<run-id>.json  the stage as one JobResult-shaped report (jobName, runId,
#        status, exitCode, inputValidation, outputValidation, metrics, errorMessage, as every job
#        report has), plus `studies`: each study's full result. Failed studies are split in two:
#          - a DATA failure (no usable input, or the job/validation rejected the study's data) is
#            the study's problem, not the run's: a WARNING (STUDY_INPUT_UNUSABLE in inputValidation,
#            STUDY_EXCLUDED in outputValidation), listed in metrics.excludedStudyIds, left out of
#            the later stages, and listed for data review. The stage still succeeds, with warnings.
#          - any other failure (infrastructure, timeout, unknown, config) or a study that never
#            started is the run's problem: an ERROR (STUDY_FAILED / STUDY_NOT_STARTED), listed in
#            metrics.rerunStudyIds, and the stage fails.
#        A study with warnings is a WARNING issue; ../common/validate-report.sh reads it too, and
#        exits 10 (UNSTABLE) for any WARNING issue, an excluded study's included.
#   split-allconcepts-report-<run-id>.md    the same, to read: totals, then each failed study with
#        its reason, job error, failed checks, logs, artifacts and the re-run setting, then the
#        studies that did not start or had warnings, then a table of every study.
#
# Prints a short summary for the console: the result, the totals, one line per failed study.
#
# Usage: split-report.sh <results-dir> <run-id> <out-dir>
# Environment (optional):
#   STAGE_STARTED_EPOCH   when the stage started (default: the earliest study start)
#   LARGE_STUDY_GB        recorded in the metrics
#   POOL_WORKERS          recorded in the metrics
#
# Exit: 0 every study succeeded | 10 succeeded, some with warnings or excluded for data problems |
#       1 any study failed for a non-data reason or did not start, or there were no results | 64 usage
set -uo pipefail

RESULTS="${1:-}"
RUN_ID="${2:-}"
OUT="${3:-}"
if [[ -z "$RESULTS" || -z "$RUN_ID" || -z "$OUT" || ! -d "$RESULTS" ]]; then
  echo "usage: $0 <results-dir> <run-id> <out-dir>" >&2
  exit 64
fi
mkdir -p "$OUT"
JSON="$OUT/split-allconcepts-report-$RUN_ID.json"
MD="$OUT/split-allconcepts-report-$RUN_ID.md"

FILES=()
while IFS= read -r f; do FILES+=("$f"); done < <(find "$RESULTS" -name study-result.json -type f | sort)
if (( ${#FILES[@]} == 0 )); then
  echo "split-report: no study-result.json under $RESULTS" >&2
  exit 1
fi

# Unreadable result files are reported as failed studies rather than dropped.
ALL="$OUT/.all-results.json"
: > "$ALL.lines"
for f in "${FILES[@]}"; do
  if ! jq -c . "$f" >> "$ALL.lines" 2>/dev/null; then
    jq -n -c --arg f "$f" '{studyId: ($f | split("/") | .[-2]), lane: "unknown", outcome: "FAILED",
        failureStage: "report", failureReason: ("study-result.json is not valid JSON: " + $f),
        validation: {failedChecks: [], warnings: []}, debug: {artifacts: ($f | split("/") | .[:-1] | join("/"))}}' >> "$ALL.lines"
  fi
done
jq -s 'sort_by(.studyId)' "$ALL.lines" > "$ALL"
rm -f "$ALL.lines"

jq --arg run "$RUN_ID" --arg started "${STAGE_STARTED_EPOCH:-}" --arg finished "$(date +%s)" \
   --arg largeGb "${LARGE_STUDY_GB:-}" --arg poolWorkers "${POOL_WORKERS:-}" '
  def num: if . == "" or . == null then null else tonumber end;
  def of($o): map(select(.outcome == $o));
  def counts: { error: map(select(.severity == "ERROR")) | length,
                warning: map(select(.severity == "WARNING")) | length,
                info: map(select(.severity == "INFO")) | length };
  def sum_metric($m): map(.job.metrics[$m] // 0 | if type == "number" then . else 0 end) | add // 0;
  # The study'"'"'s data, not the run, is at fault: no usable input, the job rejected the data, or the
  # job ran and its output failed validation.
  def data_failure: .failureStage == "preflight" or .failureStage == "validation"
                    or (.exitName == "DATA_ERROR" or .exitName == "VALIDATION_FAILED");
  . as $all
  | ($all | of("FAILED")) as $failed
  | ($all | of("NOT_STARTED")) as $unstarted
  | ($all | of("SUCCESS_WITH_WARNINGS")) as $warned
  | ($all | of("SUCCESS")) as $ok
  | ($failed | map(select(.failureStage == "preflight"))) as $preflight
  | ($failed | map(select(data_failure))) as $excluded
  | ($failed | map(select(data_failure | not))) as $broken
  | (($started | num) // ($all | map(.startedAt // empty | fromdateiso8601) | min) // ($finished | tonumber)) as $t0
  | [$preflight[] | {severity: "WARNING", code: "STUDY_INPUT_UNUSABLE", message: .failureReason,
                     location: .studyId, details: {inputUri: .inputUri}}] as $input_issues
  | ( [ ($excluded - $preflight)[] | {severity: "WARNING", code: "STUDY_EXCLUDED", message: .failureReason, location: .studyId,
                                       details: {stage: .failureStage, exitName: .exitName, runId: .runId,
                                                 runnerLog: .debug.runnerLog, artifacts: .debug.artifacts}} ]
     + [ $broken[] | {severity: "ERROR", code: "STUDY_FAILED", message: .failureReason, location: .studyId,
                      details: {stage: .failureStage, exitName: .exitName, runId: .runId,
                                runnerLog: .debug.runnerLog, artifacts: .debug.artifacts}} ]
     + [ $unstarted[] | {severity: "ERROR", code: "STUDY_NOT_STARTED", message: .failureReason, location: .studyId, details: {}} ]
     + [ $warned[] | {severity: "WARNING", code: "STUDY_WARNINGS", message: (.validation.warnings | join("; ")),
                      location: .studyId, details: {runId: .runId, artifacts: .debug.artifacts}} ] ) as $output_issues
  | (($broken | length) + ($unstarted | length)) as $bad
  | {
      jobName: "split-allconcepts",
      jobType: "MIGRATION",
      runId: $run,
      exitCode: (if $bad > 0 then "VALIDATION_FAILED"
                 elif ($warned | length) > 0 or ($excluded | length) > 0 then "SUCCESS_WITH_WARNINGS" else "SUCCESS" end),
      status: (if $bad > 0 then "FAILED" else "SUCCESS" end),
      startedAt: ($t0 | todate),
      finishedAt: ($finished | tonumber | todate),
      durationMillis: ((($finished | tonumber) - $t0) * 1000),
      inputValidation: {phase: "input", issues: $input_issues, counts: ($input_issues | counts)},
      outputValidation: {phase: "output", issues: $output_issues, counts: ($output_issues | counts)},
      metrics: {
        studies: ($all | length),
        succeeded: ($ok | length),
        succeededWithWarnings: ($warned | length),
        failed: ($failed | length),
        notStarted: ($unstarted | length),
        excluded: ($excluded | length),
        excludedStudyIds: ($excluded | map(.studyId)),
        rerunStudyIds: (($broken + $unstarted) | map(.studyId) | sort),
        poolLane: ($all | map(select(.lane == "pool")) | length),
        largeLane: ($all | map(select(.lane == "large")) | length),
        retried: ($all | map(select((.attempts // 0) > 1)) | length),
        failuresByStage: ($failed | group_by(.failureStage) | map({key: (.[0].failureStage // "unknown"), value: length}) | from_entries),
        failuresByExitName: ($failed | map(select(.exitName != null)) | group_by(.exitName) | map({key: .[0].exitName, value: length}) | from_entries),
        totalRows: ($all | sum_metric("totalRows")),
        unmappedIds: ($all | sum_metric("unmappedIds")),
        noConsentRows: ($all | sum_metric("noConsentRows")),
        malformedRows: ($all | sum_metric("malformedRows")),
        inputBytes: ($all | map(.inputBytes // 0) | add // 0),
        largeStudyGb: ($largeGb | num),
        poolWorkers: ($poolWorkers | num)
      },
      errorMessage: (if $bad == 0 then null
                     else "\($bad) of \($all | length) studies need a re-run (\($broken | length) failed for a reason other than their data, \($unstarted | length) did not start): \(($broken + $unstarted) | map(.studyId) | join(", "))" end),
      studies: $all
    }' "$ALL" > "$JSON.tmp" || { echo "split-report: could not build $JSON" >&2; rm -f "$JSON.tmp" "$ALL"; exit 1; }
mv "$JSON.tmp" "$JSON"
rm -f "$ALL"

jq -r --arg json "$(basename "$JSON")" '
  def cell: tostring | gsub("[\r\n]+"; " ") | gsub("\\|"; "\\|");
  def pad2: tostring | if length < 2 then "0" + . else . end;
  def dur: if . == null then "" else (. / 1000 | floor) as $s
           | if $s >= 3600 then "\($s / 3600 | floor)h \(($s % 3600) / 60 | floor | pad2)m"
             elif $s >= 60 then "\($s / 60 | floor)m \($s % 60 | pad2)s" else "\($s)s" end end;
  def size: if . == null then "" elif . >= 1073741824 then "\(. / 1073741824 * 10 | floor / 10) GiB"
            elif . >= 1048576 then "\(. / 1048576 * 10 | floor / 10) MiB" else "\(.) B" end;
  def lane: {pool: "runner pool", large: "own instance (large)", preflight: "not run (pre-flight)"}[.] // .;
  def outcome: {SUCCESS: "succeeded", SUCCESS_WITH_WARNINGS: "warnings", FAILED: "**FAILED**", NOT_STARTED: "not started"}[.] // .;
  .metrics as $m
  | (.studies | map(select(.outcome == "FAILED")) | sort_by(.failureStage, .studyId)) as $failed
  | (.studies | map(select(.outcome == "NOT_STARTED"))) as $unstarted
  | (.studies | map(select(.outcome == "SUCCESS_WITH_WARNINGS"))) as $warned
  | [
      "# Split AllConcepts report: \(.runId)",
      "",
      (if .status == "SUCCESS" and $m.succeededWithWarnings == 0 and ($m.excluded // 0) == 0 then "**Result: SUCCESS.** All \($m.studies) studies were split and validated."
       elif .status == "SUCCESS" then "**Result: SUCCESS WITH WARNINGS.** \($m.studies - ($m.excluded // 0)) of \($m.studies) studies were split; \($m.succeededWithWarnings) reported warnings and \($m.excluded // 0) were excluded for data problems (listed below, and left out of the later stages)."
       else "**Result: FAILED.** \(($m.rerunStudyIds // []) | length) of \($m.studies) studies need a re-run (\($m.failed - ($m.excluded // 0)) failed for a reason other than their data, \($m.notStarted) did not start); \($m.excluded // 0) were excluded for data problems. Failed studies are listed first, each with what went wrong and where to look." end),
      "",
      "Stage ran \(.startedAt) to \(.finishedAt) (\(.durationMillis | dur)). Machine-readable version: `\($json)`.",
      "",
      "| Outcome | Studies |", "|---|---|",
      "| Succeeded | \($m.succeeded) |",
      "| Succeeded with warnings | \($m.succeededWithWarnings) |",
      "| Failed: data problem, excluded | \($m.excluded // 0) |",
      "| Failed: other, re-run needed | \($m.failed - ($m.excluded // 0)) |",
      "| Did not start | \($m.notStarted) |",
      "| **Total** | **\($m.studies)** |",
      "",
      "| Lane | Studies |", "|---|---|",
      "| Runner pool | \($m.poolLane)\(if $m.poolWorkers then " (\($m.poolWorkers) workers)" else "" end) |",
      "| Own instance (input of \($m.largeStudyGb // "?") GiB or more) | \($m.largeLane) |",
      "",
      "Rows split: \($m.totalRows). Unmapped HPDS ids: \($m.unmappedIds). Rows without a consent: \($m.noConsentRows). Input read: \($m.inputBytes | size). Studies retried after an infrastructure error: \($m.retried).",
      "",
      (if ($failed | length) > 0 then
        "## Failed studies", "",
        ($failed[] |
          "### \(.studyId)\(if .abbreviation then " (\(.abbreviation))" else "" end): failed in \(.failureStage // "unknown")\(if ((.studyId) as $id | $m.excludedStudyIds // [] | index($id)) then " (data problem: excluded, needs data review)" else " (re-run needed)" end)", "",
          "- **Reason:** \(.failureReason // "unknown" | cell)",
          (if .job.errorMessage then "- **Job error:** \(.job.errorMessage | cell)" else empty end),
          (if (.job.issues // []) | length > 0 then "- **Job issues:** \(.job.issues | map("[\(.severity)] \(.code): \(.message)\(if .location then " (\(.location))" else "" end)") | join("; ") | cell)" else empty end),
          (if .failureStage == "validation" and ((.validation.failedChecks // []) | length > 0)
           then "- **Failed checks:** \(.validation.failedChecks | join("; ") | cell)" else empty end),
          (if .exitCode != null then "- **Runner:** exit \(.exitCode) (\(.exitName)), \(.attempts) attempt(s)\(if .runner.phase then ", ended in phase \(.runner.phase)" else "" end)\(if .debug.worker then ", worker \(.debug.worker)" else "" end)" else empty end),
          (if .debug.runnerLog then "- **Runner log:** `\(.debug.runnerLog)`" else empty end),
          (if .debug.workerLog then "- **Worker log:** `\(.debug.workerLog)`" else empty end),
          (if .debug.build then "- **Build:** \(.debug.build)" else empty end),
          (if .inputUri then "- **Input:** `\(.inputUri)`\(if .inputBytes then " (\(.inputBytes | size))" else "" end)" else empty end),
          "- **Artifacts:** `\(.debug.artifacts)/` (study-result.json, reports/, validation.txt, monitor logs)",
          "- **Re-run only this study:** `STUDY_FILTER=\(.studyId)`",
          ""),
        empty
       else empty end),
      (if ($unstarted | length) > 0 then
        "## Did not start", "", "| Study | Why |", "|---|---|",
        ($unstarted[] | "| \(.studyId | cell) | \(.failureReason // "" | cell) |"), ""
       else empty end),
      (if ($warned | length) > 0 then
        "## Studies with warnings", "", "| Study | Warnings | Unmapped ids | Rows without consent |", "|---|---|---|---|",
        ($warned[] | "| \(.studyId | cell) | \(.validation.warnings | join("; ") | cell) | \(.job.metrics.unmappedIds // "") | \(.job.metrics.noConsentRows // "") |"), ""
       else empty end),
      "## All studies", "",
      "| Study | Abv | Lane | Outcome | Exit | Rows | Consents | Unmapped | No consent | Input | Duration | Attempts | Worker or build |",
      "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
      (.studies[] | "| \(.studyId | cell) | \(.abbreviation // "" | cell) | \(.lane | lane) | \(.outcome | outcome) | \(.exitName // "" ) | \(.job.metrics.totalRows // "") | \(if .job then (.job.metrics.rowsPerConsent // {} | length) else "" end) | \(.job.metrics.unmappedIds // "") | \(.job.metrics.noConsentRows // "") | \(.inputBytes | size) | \(.durationMillis | dur) | \(.attempts // 0) | \(.debug.worker // .debug.build // "" | cell) |")
    ] | .[]' "$JSON" > "$MD" || { echo "split-report: could not build $MD" >&2; exit 1; }

# --- console summary -------------------------------------------------------------------------------
jq -r '
  .metrics as $m
  | "================ Split AllConcepts summary ================",
    "  result: \(.status)\(if .exitCode == "SUCCESS_WITH_WARNINGS" then " (with warnings)" else "" end)",
    "  \($m.succeeded) succeeded, \($m.succeededWithWarnings) with warnings, \($m.failed) failed (\($m.excluded // 0) excluded for data problems), \($m.notStarted) did not start (of \($m.studies))",
    (.studies[] | select(.outcome == "FAILED" or .outcome == "NOT_STARTED")
      | "  \(.studyId)  \(.outcome)  [\(.failureStage // "-")] \(.failureReason // "" | gsub("[\r\n]+"; " "))"),
    "  full report: \(input_filename | sub("\\.json$"; ".md"))",
    "============================================================"' "$JSON"

status=$(jq -r '.exitCode' "$JSON")
case "$status" in
  SUCCESS) exit 0 ;;
  SUCCESS_WITH_WARNINGS) exit 10 ;;
  *) exit 1 ;;
esac
