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
# xtrace is NOT enabled: this script handles database credentials, and `set -x` would echo them into the
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
# SRCE RHEL9 golden image, initialised the way the pheno ETL environment's runners are
# (avillach-jenkins-bdc-etl: pipelines/hpds-ingest/terraform/user_data.sh.integration.tpl):
# podman is the container runtime, enabled through the SRCE startup config.
say "SRCE golden image startup (podman)"
echo "ENABLE_PODMAN=true" > /opt/srce/startup.config
if [ -f /opt/srce/scripts/start-gsstools.sh ]; then
  sh /opt/srce/scripts/start-gsstools.sh
fi

say "Installing runtime packages"
dnf -y update
dnf install -y podman jq
command -v aws >/dev/null 2>&1 || dnf install -y awscli

# SSM only -- this instance has no SSH key. The Jenkins monitor tails the log through it.
systemctl enable --now amazon-ssm-agent

# --------------------------------------------------------------------------
PHASE=credentials
JOB_EXIT=5

# Non-secret environment, rendered by Terraform. Decoded from base64 so no heredoc
# delimiter or embedded newline in a job param value can inject into this file.
{ echo '${container_env_b64}' | base64 -d; printf '\n'; } > "$ENV_FILE"
chmod 600 "$ENV_FILE"

%{ if db_secret_id != "" ~}
say "Fetching participant DB credentials from Secrets Manager (${db_secret_id})"

# The secret exists only while the participant database is up: participant-db-start
# creates it, participant-db-stop deletes it. Not found therefore means "database down".
if ! SECRET_JSON=$(aws secretsmanager get-secret-value \
    --secret-id "${db_secret_id}" --region "${aws_region}" \
    --query SecretString --output text 2>/tmp/secret.err); then
  say "ERROR: could not read ${db_secret_id}: $(cat /tmp/secret.err)"
  say "  The participant database is probably not running -- run participant-db-start first."
  exit 5
fi

say "Secret keys: $(printf '%s' "$SECRET_JSON" | jq -r 'keys | join(", ")' 2>/dev/null || echo '(not valid JSON)')"

# Accept either a ready-made JDBC url or discrete host/port/dbname fields (what
# participant-db-start writes).
JQ_ERR=$(mktemp)
DB_URL=$(printf '%s' "$SECRET_JSON" | jq -r '
  def nonempty: if (. // "") == "" then null else . end;
  if (.url | nonempty) then .url
  elif (.jdbcUrl | nonempty) then .jdbcUrl
  else "jdbc:postgresql://"
    + ((.host | nonempty) // error("no host in secret"))
    + ":" + (((.port | nonempty) // 5432) | tostring)
    + "/" + (((.dbname | nonempty) // (.dbName | nonempty)) // error("no dbname in secret"))
  end' 2>"$JQ_ERR") || true

if [[ ! "$DB_URL" =~ ^jdbc: ]]; then
  say "ERROR: secret yielded no JDBC url"
  [[ -s "$JQ_ERR" ]] && say "  jq error: $(cat "$JQ_ERR")"
  rm -f "$JQ_ERR"
  exit 5
fi
rm -f "$JQ_ERR"

# Credentials go into --env-file, never -e: this keeps them out of the process table,
# `podman inspect`, and any command echo.
{
  printf 'DB_URL=%s\n' "$DB_URL"
  printf 'DB_USERNAME=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.username')"
  printf 'DB_PASSWORD=%s\n' "$(printf '%s' "$SECRET_JSON" | jq -r '.password')"
} >> "$ENV_FILE"
unset SECRET_JSON DB_URL
say "Participant DB credentials resolved"
%{ else ~}
say "Job does not use the participant database; skipping credential fetch"
%{ endif ~}

# --------------------------------------------------------------------------
PHASE=image
JOB_EXIT=4
# Built and saved (docker build / docker save | gzip) on the Jenkins agent, loaded here with
# podman -- the same split the pheno ETL environment's hpds-ingest runners use.
say "Loading container image ${image_tar}"
aws s3 cp "s3://${stack_s3_bucket}/etl-runner/container/${image_tar}" /tmp/image.tar.gz \
  --region "${aws_region}" --no-progress
gunzip -c /tmp/image.tar.gz | podman load
rm -f /tmp/image.tar.gz

# --------------------------------------------------------------------------
PHASE=job

say "Running job ${job_name} (run ${run_id})"
set +e
# :Z relabels the reports directory so the container may write it with SELinux enforcing.
podman run --rm \
  --env-file "$ENV_FILE" \
  -v "$REPORTS":/reports:Z \
  "${image_name}"
JOB_EXIT=$?
set -e
say "Job ${job_name} exited $JOB_EXIT ($(exit_name "$JOB_EXIT"))"

PHASE=done
# The EXIT trap uploads artifacts and terminates the instance.
exit "$JOB_EXIT"
