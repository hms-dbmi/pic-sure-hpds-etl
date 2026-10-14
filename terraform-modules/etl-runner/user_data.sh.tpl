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
# Two modes:
#   single (pool_prefix empty)  boot, run the one job Terraform rendered in, terminate.
#   worker (pool_prefix set)    boot once, then run tasks from this instance's S3 mailbox,
#                               s3://<bucket>/<pool_prefix>/<instance-id>/task.json, one at a time,
#                               publishing each task's reports, log and status.json sentinel exactly
#                               as a single run would. Terminates on a `stop` file in the mailbox,
#                               after pool_idle_timeout seconds with no task, or on any boot failure.
#                               See etl-runners/runner-pool/ and etl-runners/common/pool-dispatch.sh.
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
POOL_PREFIX='${pool_prefix}'
TASK_ACTIVE=0
INSTANCE_ID=
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

# Worker mode: publishes the current task's reports, log and status.json sentinel (last), so its
# split build sees exactly what a single run would publish.
publish_task() {
  local code=$1 phase=$2
  cat > "$WORK/task-status.json" <<TASKSTATUSEOF
{
  "job": "$TASK_JOB",
  "runId": "$TASK_RUN_ID",
  "module": "${module_name}",
  "instanceId": "$INSTANCE_ID",
  "phase": "$phase",
  "exitCode": $code,
  "exitName": "$(exit_name "$code")",
  "startedAt": "$TASK_STARTED",
  "finishedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
TASKSTATUSEOF
  aws s3 sync "$REPORTS" "s3://${stack_s3_bucket}/$TASK_REPORTS/" \
    --region "${aws_region}" --no-progress || say "WARN: report sync failed"
  aws s3 cp "$WORK/task.log" "s3://${stack_s3_bucket}/$TASK_LOG_KEY" \
    --region "${aws_region}" --no-progress || say "WARN: task log upload failed"
  aws s3 cp "$WORK/task-status.json" "s3://${stack_s3_bucket}/$TASK_REPORTS/status.json" \
    --region "${aws_region}" --no-progress || say "WARN: task status upload failed"
  shred -u "$WORK/task.env" 2>/dev/null || rm -f "$WORK/task.env"
  TASK_ACTIVE=0
}

# Runs on every exit path. Publishes artifacts, then the sentinel, then terminates.
finish() {
  set +e
  local instance_id
  instance_id=$(imds instance-id)

  say "Finishing: phase=$PHASE exit=$JOB_EXIT ($(exit_name "$JOB_EXIT"))"

  if [[ -n "$POOL_PREFIX" ]]; then
    # A task cut short by a crash still gets a sentinel, so its build fails now as an
    # infrastructure error instead of waiting out its timeout.
    if [[ "$TASK_ACTIVE" == 1 ]]; then
      publish_task 4 "$PHASE"
    fi
    shred -u "$ENV_FILE" 2>/dev/null || rm -f "$ENV_FILE"
    aws s3 cp "$LOG" "s3://${stack_s3_bucket}/etl-runner/logs/${module_name}-${run_id}-$instance_id.log" \
      --region "${aws_region}" --no-progress || say "WARN: worker log upload failed"
    sync
    say "Terminating worker"
    shutdown now
    return
  fi

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
say "SRCE golden image startup done"

# No `dnf -y update`: the golden image is rebuilt monthly, and a full update on every boot of a
# short-lived instance cost minutes. Only the packages below are installed.
say "Installing runtime packages"
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

%{ if uses_db ~}
say "Reading participant DB credentials"

# Passed in by the Jenkins job from the credentials participant-db-start saved on the Jenkins
# server (base64, so no character in them can break out of this script). Empty means the
# database was not up when this runner was provisioned.
SECRET_JSON=$(echo '${db_credentials_b64}' | base64 -d)
if [[ -z "$SECRET_JSON" ]]; then
  say "ERROR: no participant DB credentials were passed to this runner."
  say "  The participant database is probably not running -- run participant-db-start first."
  exit 5
fi

say "Credential keys: $(printf '%s' "$SECRET_JSON" | jq -r 'keys | join(", ")' 2>/dev/null || echo '(not valid JSON)')"

# Accept either a ready-made JDBC url or discrete host/port/dbname fields (what
# participant-db-start saves).
JQ_ERR=$(mktemp)
DB_URL=$(printf '%s' "$SECRET_JSON" | jq -r '
  def nonempty: if (. // "") == "" then null else . end;
  if (.url | nonempty) then .url
  elif (.jdbcUrl | nonempty) then .jdbcUrl
  else "jdbc:postgresql://"
    + ((.host | nonempty) // error("no host in credentials"))
    + ":" + (((.port | nonempty) // 5432) | tostring)
    + "/" + (((.dbname | nonempty) // (.dbName | nonempty)) // error("no dbname in credentials"))
  end' 2>"$JQ_ERR") || true

if [[ ! "$DB_URL" =~ ^jdbc: ]]; then
  say "ERROR: credentials yielded no JDBC url"
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
%{ if pool_prefix != "" ~}
PHASE=idle
JOB_EXIT=4
INSTANCE_ID=$(imds instance-id)
if [[ -z "$INSTANCE_ID" ]]; then
  say "ERROR: could not read this instance's id from instance metadata"
  exit 4
fi
MAILBOX="s3://${stack_s3_bucket}/$POOL_PREFIX/$INSTANCE_ID"

# Runs one task: validates it, builds its env file (the boot env minus the pool's own job
# identity, plus the task's), runs the container, and publishes the results.
run_task() {
  TASK_RUN_ID=$(jq -r '.runId // empty' "$WORK/task.json")
  TASK_JOB=$(jq -r '.env.ETL_JOB // empty' "$WORK/task.json")
  TASK_REPORTS=$(jq -r '.reportsPrefix // empty' "$WORK/task.json")
  TASK_LOG_KEY=$(jq -r '.logKey // empty' "$WORK/task.json")
  TASK_STARTED=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  local safe='^[A-Za-z0-9._-]+$' safe_key='^[A-Za-z0-9._/-]+$'
  if [[ ! "$TASK_RUN_ID" =~ $safe || ! "$TASK_JOB" =~ $safe \
        || ! "$TASK_REPORTS" =~ $safe_key || ! "$TASK_LOG_KEY" =~ $safe_key \
        || "$TASK_REPORTS" == *..* || "$TASK_LOG_KEY" == *..* ]]; then
    say "ERROR: rejecting a malformed task (run id, job, reports prefix or log key)"
    return 0
  fi

  TASK_ACTIVE=1
  rm -rf "$REPORTS" && mkdir -p "$REPORTS"
  : > "$WORK/task.log"
  say "Task $TASK_RUN_ID: running $TASK_JOB"

  # Only the job identity and job parameters may come from a task, one line each.
  local bad
  bad=$(jq -r '.env | to_entries[]
    | select((.key | test("^(ETL_JOB|ETL_RUN_ID|ETL_PARAM_[A-Za-z0-9_]+)$") | not)
             or ((.value | type) != "string") or (.value | test("[\\r\\n]")))
    | .key' "$WORK/task.json" 2>/dev/null || echo "(unreadable env)")
  if [[ -n "$bad" ]]; then
    say "ERROR: task $TASK_RUN_ID has disallowed env entries: $bad"
    publish_task 5 task
    return 0
  fi
  ( umask 077
    grep -v -E '^(ETL_JOB|ETL_RUN_ID|ETL_PARAM_[A-Za-z0-9_]*)=' "$ENV_FILE" > "$WORK/task.env"
    jq -r '.env | to_entries[] | "\(.key)=\(.value)"' "$WORK/task.json" >> "$WORK/task.env" )

  PHASE=job
  set +e
  podman run --rm \
    --env-file "$WORK/task.env" \
    -v "$REPORTS":/reports:Z \
    "${image_name}" 2>&1 | tee -a "$WORK/task.log"
  local code=$${PIPESTATUS[0]}
  set -e
  PHASE=idle
  say "Task $TASK_RUN_ID: $TASK_JOB exited $code ($(exit_name "$code"))"
  publish_task "$code" done
}

say "Worker ready; waiting for tasks in $MAILBOX/ (idle limit ${pool_idle_timeout}s)"
IDLE_SINCE=$(date +%s)
while true; do
  if aws s3 ls "$MAILBOX/stop" --region "${aws_region}" >/dev/null 2>&1; then
    say "Stop requested"
    PHASE=stopped
    JOB_EXIT=0
    exit 0
  fi
  if aws s3 cp "$MAILBOX/task.json" "$WORK/task.json" --region "${aws_region}" --only-show-errors >/dev/null 2>&1; then
    # Claimed: this mailbox has a single reader, and the dispatcher writes the next task only
    # after this one's sentinel exists.
    aws s3 rm "$MAILBOX/task.json" --region "${aws_region}" --only-show-errors || true
    run_task
    rm -f "$WORK/task.json"
    IDLE_SINCE=$(date +%s)
    continue
  fi
  if (( $(date +%s) - IDLE_SINCE >= ${pool_idle_timeout} )); then
    say "No task for ${pool_idle_timeout}s; shutting down"
    PHASE=idle-timeout
    JOB_EXIT=0
    exit 0
  fi
  sleep 10
done
%{ else ~}
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
%{ endif ~}
