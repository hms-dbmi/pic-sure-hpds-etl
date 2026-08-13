#!/bin/bash
#
# Ephemeral hpds-etl runner bootstrap.
#
# Rendered by Terraform's templatefile(). Every $${...} in this file is a Terraform
# placeholder, so bash variables are written WITHOUT braces ($VAR, not $-brace-VAR) to
# keep the two syntaxes from colliding. If you ever need a braced bash expansion here,
# double the dollar sign to escape it from Terraform.
#
# Contract with Jenkins:
#   - all output lands in /var/log/etl-pipeline.log, uploaded to S3 on every exit path
#   - the job's ExitCode is written to status.json, uploaded LAST as the completion
#     sentinel: the monitor treats "status.json exists" as "the run is over, and every
#     other artifact is already in S3"
#   - the instance always terminates -- normal completion, error, OOM kill, or spot reclaim
#
# xtrace is deliberately NOT enabled: this script handles RDS credentials and `set -x`
# would echo them into the log that gets uploaded to S3.
set -euo pipefail

LOG=/var/log/etl-pipeline.log
WORK=/var/etl
REPORTS=$WORK/reports
ENV_FILE=$WORK/etl.env
STATUS=$WORK/status.json

mkdir -p "$WORK" "$REPORTS"
chmod 700 "$WORK"
touch "$LOG"
exec > >(tee -a "$LOG") 2>&1

STARTED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
PHASE=boot
# Pre-seeded with the code this phase's failure should report. Bootstrap problems are
# infrastructure (4) so Jenkins retries them; the job phase overwrites this with the
# JAR's real ExitCode.
JOB_EXIT=4

say() { echo "[$(date -u +%H:%M:%S)] [${module_name}] $*"; }

exit_name() {
  case "$1" in
    0) echo SUCCESS ;;
    2) echo VALIDATION_FAILED ;;
    3) echo DATA_ERROR ;;
    4) echo INFRASTRUCTURE_ERROR ;;
    5) echo CONFIG_ERROR ;;
    *) echo UNKNOWN ;;
  esac
}

imds() {
  local token
  token=$(curl -sf -m 5 -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) || return 0
  curl -sf -m 5 -H "X-aws-ec2-metadata-token: $token" \
    "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null || true
}

# Runs on every exit path. Publishes artifacts, then the sentinel, then terminates.
finish() {
  set +e
  local instance_id
  instance_id=$(imds instance-id)

  say "Finishing: phase=$PHASE exit=$JOB_EXIT ($(exit_name "$JOB_EXIT"))"

  # Never leave credentials on a volume that could outlive an aborted terminate.
  shred -u "$ENV_FILE" 2>/dev/null || rm -f "$ENV_FILE"

  cat > "$STATUS" <<STATUSEOF
{
  "job": "${job_name}",
  "runId": "${run_id}",
  "module": "${module_name}",
  "instanceId": "$instance_id",
  "phase": "$PHASE",
  "exitCode": $JOB_EXIT,
  "exitName": "$(exit_name "$JOB_EXIT")",
  "startedAt": "$STARTED_AT",
  "finishedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
STATUSEOF

  aws s3 sync "$REPORTS" "s3://${stack_s3_bucket}/${reports_prefix}/" \
    --region "${aws_region}" --no-progress || say "WARN: report sync failed"
  aws s3 cp "$LOG" "s3://${stack_s3_bucket}/etl-runner/logs/${module_name}-${run_id}.log" \
    --region "${aws_region}" --no-progress || say "WARN: log upload failed"

  # Sentinel last: its presence means every other artifact is already in S3.
  aws s3 cp "$STATUS" "s3://${stack_s3_bucket}/${reports_prefix}/status.json" \
    --region "${aws_region}" --no-progress || say "WARN: status upload failed"

  sync
  say "Terminating instance"
  shutdown now
}
trap finish EXIT

# --------------------------------------------------------------------------
PHASE=install
say "Installing runtime packages"
dnf update -y
dnf install -y docker jq amazon-ssm-agent
command -v aws >/dev/null 2>&1 || dnf install -y awscli

systemctl enable --now docker
# SSM only -- this instance has no SSH key. The Jenkins monitor tails the log through it.
systemctl enable --now amazon-ssm-agent

# --------------------------------------------------------------------------
PHASE=credentials
JOB_EXIT=5
say "Fetching RDS credentials from Secrets Manager (${rds_secret_id})"

# Non-secret environment, rendered by Terraform. Quoted heredoc: values stay literal.
cat > "$ENV_FILE" <<'ENVEOF'
${container_env}
ENVEOF
chmod 600 "$ENV_FILE"

SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "${rds_secret_id}" --region "${aws_region}" \
  --query SecretString --output text)

# Accept either a ready-made JDBC url or discrete host/port/dbname fields.
# Credentials go into --env-file, never -e: they stay out of the process table,
# `docker inspect`, and any command echo.
{
  printf 'RDS_URL=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '
    if .url then .url
    elif .jdbcUrl then .jdbcUrl
    else "jdbc:postgresql://" + .host + ":" + ((.port // 5432) | tostring) + "/" + (.dbname // .dbName // "hpds")
    end')"
  printf 'RDS_USERNAME=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.username')"
  printf 'RDS_PASSWORD=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.password')"
} >> "$ENV_FILE"
unset SECRET_JSON

grep -q '^RDS_URL=jdbc:' "$ENV_FILE" || { say "ERROR: secret yielded no JDBC url"; exit 5; }
say "RDS credentials resolved"

# --------------------------------------------------------------------------
PHASE=image
JOB_EXIT=4
say "Loading container image ${image_tar}"
aws s3 cp "s3://${stack_s3_bucket}/etl-runner/container/${image_tar}" /tmp/image.tar.gz \
  --region "${aws_region}" --no-progress
gunzip -c /tmp/image.tar.gz | docker load
rm -f /tmp/image.tar.gz

# --------------------------------------------------------------------------
PHASE=job
say "Running job ${job_name} (run ${run_id})"
set +e
docker run --rm \
  --env-file "$ENV_FILE" \
  -v "$REPORTS":/reports \
  "${image_name}"
JOB_EXIT=$?
set -e
say "Job ${job_name} exited $JOB_EXIT ($(exit_name "$JOB_EXIT"))"

PHASE=done
# The EXIT trap uploads artifacts and terminates the instance.
exit "$JOB_EXIT"
