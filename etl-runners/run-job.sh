#!/bin/bash
#
# Container entrypoint. Runs one hpds-etl job and exits with its ExitCode.
#
# Input comes from the environment rather than argv so the generated EC2 user-data never has to
# quote a command line:
#
#   ETL_JOB              required   --job=<name>
#   ETL_RUN_ID           required   --run-id=<id>
#   ETL_PARAM_<key>      optional   --<key-with-hyphens>=<value>, e.g.
#                                   ETL_PARAM_study_id=phs001412 -> --study-id=phs001412
#   ETL_REPORTS_DIR      optional   where the JSON report is written (default /reports)
#   JAVA_OPTS            optional   JVM flags
#
# Extra arguments passed to the container are appended verbatim, so `docker run hpds-etl-runner
# --help` still works. Values must not contain newlines: they arrive through a docker --env-file.
set -uo pipefail

JAR=/app/hpds-etl.jar
REPORTS_DIR="${ETL_REPORTS_DIR:-/reports}"

# Passthrough mode: explicit args win over the environment contract.
if [[ $# -gt 0 && "$1" == --* ]]; then
  exec java ${JAVA_OPTS:-} -jar "$JAR" "$@"
fi

: "${ETL_JOB:?ETL_JOB is required}"
: "${ETL_RUN_ID:?ETL_RUN_ID is required}"

ARGS=("--job=$ETL_JOB" "--run-id=$ETL_RUN_ID")

# IFS='=' with two read targets splits on the first '=' only, so values containing '=' survive.
while IFS='=' read -r name value; do
  [[ -z "$name" ]] && continue
  key="${name#ETL_PARAM_}"
  ARGS+=("--${key//_/-}=$value")
done < <(env | grep '^ETL_PARAM_' | sort)

mkdir -p "$REPORTS_DIR"

echo "[run-job] job=$ETL_JOB run-id=$ETL_RUN_ID reports=$REPORTS_DIR"
echo "[run-job] args: ${ARGS[*]}"

START=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
java ${JAVA_OPTS:-} -jar "$JAR" "${ARGS[@]}" "$@"
EXIT=$?
FINISH=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

case "$EXIT" in
  0) NAME=SUCCESS ;;
  2) NAME=VALIDATION_FAILED ;;
  3) NAME=DATA_ERROR ;;
  4) NAME=INFRASTRUCTURE_ERROR ;;
  5) NAME=CONFIG_ERROR ;;
  *) NAME=UNKNOWN ;;
esac

# Records the invocation next to the report the JAR wrote, so a report can be traced back to its
# arguments without reading the console log. The host writes the authoritative status.json.
cat > "$REPORTS_DIR/container-status.json" <<EOF
{
  "job": "$ETL_JOB",
  "runId": "$ETL_RUN_ID",
  "exitCode": $EXIT,
  "exitName": "$NAME",
  "args": "${ARGS[*]}",
  "startedAt": "$START",
  "finishedAt": "$FINISH"
}
EOF

echo "[run-job] exit=$EXIT ($NAME)"
echo "[run-job] artifacts in $REPORTS_DIR:"
ls -l "$REPORTS_DIR" || true

exit "$EXIT"
