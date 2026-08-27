#!/bin/bash
#
# Ephemeral hpds-etl runner bootstrap.
#
# Rendered by Terraform's templatefile(). Every $${...} here is a Terraform placeholder, so bash
# variables are written without braces to keep the two syntaxes apart. A braced bash expansion
# must double the dollar sign to escape it from Terraform.
#
# Contract with Jenkins:
#   - all output lands in /var/log/etl-pipeline.log, uploaded to S3 on every exit path
#   - the job's ExitCode is written to status.json, uploaded LAST as the completion sentinel: its
#     presence means the run is over and every other artifact is already in S3
#   - the instance always terminates -- normal completion, error, OOM kill, or spot reclaim
#
# xtrace is NOT enabled: this script handles RDS credentials, and `set -x` would echo them into the
# log uploaded to S3.
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
# infrastructure (4) so Jenkins retries them; the job phase overwrites this.
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

# Non-secret environment, rendered by Terraform. Decoded from base64 so no heredoc
# delimiter or embedded newline in a job param value can inject into this file.
{ echo '${container_env_b64}' | base64 -d; printf '\n'; } > "$ENV_FILE"
chmod 600 "$ENV_FILE"

SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "${rds_secret_id}" --region "${aws_region}" \
  --query SecretString --output text)

say "Secret keys: $(printf '%s' "$SECRET_JSON" | jq -r 'keys | join(", ")' 2>/dev/null || echo '(not valid JSON)')"

# Accept either a ready-made JDBC url or discrete host/port/dbname fields.
# When the secret contains only username/password (e.g. an RDS-managed secret),
# fall back to the rds_host and rds_dbname provided via Terraform variables.
JQ_ERR=$(mktemp)
RDS_URL=$(printf '%s' "$SECRET_JSON" | jq -r --arg tfhost '${rds_host}' --arg tfdb '${rds_dbname}' '
  def nonempty: if (. // "") == "" then null else . end;
  if (.url | nonempty) then .url
  elif (.jdbcUrl | nonempty) then .jdbcUrl
  else "jdbc:postgresql://"
    + ((.host | nonempty) // ($tfhost | nonempty) // error("no RDS host in secret or tfvars"))
    + ":" + (((.port | nonempty) // 5432) | tostring)
    + "/" + (((.dbname | nonempty) // (.dbName | nonempty) // ($tfdb | nonempty)) // "hpds")
  end' 2>"$JQ_ERR") || true

if [[ ! "$RDS_URL" =~ ^jdbc: ]]; then
  say "ERROR: secret yielded no JDBC url"
  say "  tfhost='${rds_host}' tfdb='${rds_dbname}'"
  [[ -s "$JQ_ERR" ]] && say "  jq error: $(cat "$JQ_ERR")"
  [[ -n "$RDS_URL" ]] && say "  jq output: $RDS_URL"
  rm -f "$JQ_ERR"
  exit 5
fi
rm -f "$JQ_ERR"

# Credentials go into --env-file, never -e: this keeps them out of the process table,
# `docker inspect`, and any command echo.
{
  printf 'RDS_URL=%s\n' "$RDS_URL"
  printf 'RDS_USERNAME=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.username')"
  printf 'RDS_PASSWORD=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.password')"
} >> "$ENV_FILE"
unset SECRET_JSON RDS_URL
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
