#!/bin/bash
#
# Ephemeral hpds-etl runner bootstrap.
#
# Rendered by Terraform's templatefile() and passed to EC2 gzipped. Every $${...} here is a
# Terraform placeholder, so bash variables are written without braces to keep the two syntaxes
# apart. A braced bash expansion must double the dollar sign to escape it from Terraform.
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
#                               as a single run would. Terminates on a `stop` object in the mailbox,
#                               after pool_idle_timeout seconds with no task, or on any boot failure.
#                               See etl-runners/runner-pool/ and etl-runners/common/pool-dispatch.sh.
#
# Nothing secret is rendered into this script: cloud-init keeps copies of user data under
# /var/lib/cloud/instance/ and IMDS serves it to any process on the instance. The database
# credentials are fetched from SSM at boot instead. xtrace is NOT enabled: `set -x` would echo
# them into the log uploaded to S3.
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
CHECKPOINT_PID=
CHECKPOINT_STOP=$WORK/checkpoint.stop
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

# Publishes one run -- reports (minus *.partial), its log, then the status.json sentinel LAST, so
# the sentinel's presence means everything else is already in S3. Shared by single mode (finish)
# and worker mode (each task), so both publish the same layout and sentinel schema.
#   publish_run <job> <run-id> <module> <phase> <exit-code> <started-at> <reports-prefix> <log-file> <log-key>
publish_run() {
  local job=$1 run=$2 module=$3 phase=$4 code=$5 started=$6 prefix=$7 log_file=$8 log_key=$9
  cat > "$STATUS" <<STATUSEOF
{
  "job": "$job",
  "runId": "$run",
  "module": "$module",
  "instanceId": "$INSTANCE_ID",
  "phase": "$phase",
  "exitCode": $code,
  "exitName": "$(exit_name "$code")",
  "startedAt": "$started",
  "finishedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
STATUSEOF
  aws s3 sync "$REPORTS" "s3://${stack_s3_bucket}/$prefix/" --exclude "*.partial" \
    --region "${aws_region}" --no-progress || say "WARN: report sync failed"
  aws s3 cp "$log_file" "s3://${stack_s3_bucket}/$log_key" \
    --region "${aws_region}" --no-progress || say "WARN: log upload failed"
  aws s3 cp "$STATUS" "s3://${stack_s3_bucket}/$prefix/status.json" \
    --region "${aws_region}" --no-progress || say "WARN: status upload failed"
}

# Worker mode: a line for both the worker log and the current task's log (its caller's LOG_URI).
tsay() { say "$@" | tee -a "$WORK/task.log"; }

# Worker mode: publishes the current task, then drops its env file.
publish_task() {
  publish_run "$TASK_JOB" "$TASK_RUN_ID" "$TASK_MODULE" "$2" "$1" "$TASK_STARTED" \
    "$TASK_REPORTS" "$WORK/task.log" "$TASK_LOG_KEY"
  shred -u "$WORK/task.env" 2>/dev/null || rm -f "$WORK/task.env"
  TASK_ACTIVE=0
}

# Single mode: uploads the reports and log every few minutes while the job runs, so a run cut
# short (monitor timeout, lost instance) still leaves what it had finished in S3 -- for a
# participants-migration rerun, the studies --resume-from can skip. Reports are written as
# *.partial and renamed, so those are skipped. No status.json: that stays finish()'s alone.
checkpoint_loop() {
  while true; do
    # Five minutes, in steps short enough that stop_checkpoints is never kept waiting long.
    for _ in $(seq 60); do
      [[ -f "$CHECKPOINT_STOP" ]] && return 0
      sleep 5
    done
    aws s3 sync "$REPORTS" "s3://${stack_s3_bucket}/${reports_prefix}/" --exclude "*.partial" \
      --region "${aws_region}" --no-progress --only-show-errors || say "WARN: checkpoint report sync failed"
    [[ -f "$CHECKPOINT_STOP" ]] && return 0
    aws s3 cp "$LOG" "s3://${stack_s3_bucket}/etl-runner/logs/${module_name}-${run_id}.log" \
      --region "${aws_region}" --no-progress --only-show-errors || say "WARN: checkpoint log upload failed"
  done
}

# Lets an upload in flight finish rather than killing it: an orphaned upload finishing after
# finish()'s final copies would overwrite them with older ones.
stop_checkpoints() {
  if [[ -n "$CHECKPOINT_PID" ]]; then
    touch "$CHECKPOINT_STOP"
    wait "$CHECKPOINT_PID" 2>/dev/null || true
    CHECKPOINT_PID=
  fi
}

# Runs on every exit path. Publishes artifacts, then the sentinel, then terminates.
finish() {
  set +e
  stop_checkpoints
  [[ -n "$INSTANCE_ID" ]] || INSTANCE_ID=$(imds instance-id)

  say "Finishing: phase=$PHASE exit=$JOB_EXIT ($(exit_name "$JOB_EXIT"))"

  # Never leave credentials on a volume that could outlive an aborted terminate.
  shred -u "$ENV_FILE" 2>/dev/null || rm -f "$ENV_FILE"

  if [[ -n "$POOL_PREFIX" ]]; then
    if [[ "$TASK_ACTIVE" == 1 && "$TERMINATED" == 1 ]]; then
      # Terminated mid-task -- usually pool-retire-worker.sh after the caller gave up on the run,
      # and may already have redispatched it under the same run id elsewhere. A sentinel or
      # reports from here would land on top of that retry, so publish nothing; a caller still
      # waiting sees this instance terminate.
      say "Terminated during task $TASK_RUN_ID; publishing nothing for it"
    elif [[ "$TASK_ACTIVE" == 1 ]]; then
      # A task cut short by a crash still gets a sentinel, so its caller fails it now as an
      # infrastructure error instead of waiting out its timeout.
      publish_task 4 "$PHASE"
    fi
    aws s3 cp "$LOG" "s3://${stack_s3_bucket}/etl-runner/logs/${module_name}-${run_id}-$INSTANCE_ID.log" \
      --region "${aws_region}" --no-progress || say "WARN: worker log upload failed"
    sync
    say "Terminating worker"
    shutdown now
    return
  fi

  publish_run "${job_name}" "${run_id}" "${module_name}" "$PHASE" "$JOB_EXIT" "$STARTED_AT" \
    "${reports_prefix}" "$LOG" "etl-runner/logs/${module_name}-${run_id}.log"

  sync
  say "Terminating instance"
  shutdown now
}
trap finish EXIT
# SIGTERM (shutdown, or the instance being terminated) still exits through finish(), and lets it
# tell a termination from a crash.
TERMINATED=0
trap 'TERMINATED=1; exit 143' TERM

# --------------------------------------------------------------------------
PHASE=install
# SRCE RHEL9 golden image, initialised the way the pheno ETL environment's runners are
# (avillach-jenkins-bdc-etl: pipelines/hpds-ingest/terraform/user_data.sh.integration.tpl):
# podman is the container runtime, enabled through the SRCE startup config.
# The SRCE yum mirror can answer 403 for a minute or so after the instance registers with
# subscription management, which failed a whole run in the install phase. Retry before giving up.
retry() {
  local attempt
  for attempt in 1 2 3 4; do
    "$@" && return 0
    [ "$attempt" -lt 4 ] || break
    say "WARN: '$*' failed (attempt $attempt/4); retrying in 30s"
    sleep 30
  done
  return 1
}

say "SRCE golden image startup (podman)"
echo "ENABLE_PODMAN=true" > /opt/srce/startup.config
if [ -f /opt/srce/scripts/start-gsstools.sh ]; then
  retry sh /opt/srce/scripts/start-gsstools.sh
fi
say "SRCE golden image startup done"

# No `dnf -y update`: the golden image is rebuilt monthly, and a full update on every boot of a
# short-lived instance cost minutes. Only the packages below are installed.
say "Installing runtime packages"
retry dnf install -y podman jq
command -v aws >/dev/null 2>&1 || retry dnf install -y awscli

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
DB_PARAM='${db_credentials_parameter}'
say "Reading participant DB credentials from SSM parameter $DB_PARAM"

# participant-db-start writes this SecureString each time it rotates the password. Read as the
# instance role (ssm:GetParameter on it, plus kms:Decrypt on its key). The value goes only into
# SECRET_JSON; the AWS CLI's error text, which never contains it, goes to SSM_ERR.
SSM_ERR=$(mktemp)
SECRET_JSON=
fetch_db_credentials() {
  SECRET_JSON=$(aws ssm get-parameter --with-decryption --name "$DB_PARAM" \
    --region "${aws_region}" --query Parameter.Value --output text 2>"$SSM_ERR") || return 1
  [[ -n "$SECRET_JSON" ]]
}
if ! retry fetch_db_credentials; then
  say "ERROR: could not read participant DB credentials from SSM parameter $DB_PARAM"
  [[ -s "$SSM_ERR" ]] && say "  aws: $(tr '\n' ' ' < "$SSM_ERR")"
  say "  If the parameter does not exist, the participant database is probably not running --"
  say "  run participant-db-start first. Otherwise check the instance role's ssm:GetParameter and"
  say "  kms:Decrypt permissions on it."
  rm -f "$SSM_ERR"
  exit 5
fi
rm -f "$SSM_ERR"

say "Credential keys: $(printf '%s' "$SECRET_JSON" | jq -r 'keys | join(", ")' 2>/dev/null || echo '(not valid JSON)')"

# A missing username or password would otherwise reach the container as the literal "null".
if ! printf '%s' "$SECRET_JSON" | jq -e '
    def usable: type == "string" and . != "" and . != "null" and (test("[\r\n]") | not);
    (.username | usable) and (.password | usable)' >/dev/null 2>&1; then
  say "ERROR: $DB_PARAM is not JSON with a non-empty username and password"
  exit 5
fi

# Accept either a ready-made JDBC url or discrete host/port/dbname fields (what
# participant-db-start writes).
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

if [[ ! "$DB_URL" =~ ^jdbc: || "$DB_URL" == *$'\n'* || "$DB_URL" == *$'\r'* ]]; then
  say "ERROR: credentials yielded no JDBC url"
  [[ -s "$JQ_ERR" ]] && say "  jq error: $(cat "$JQ_ERR")"
  rm -f "$JQ_ERR"
  exit 5
fi
rm -f "$JQ_ERR"

# The participant database requires TLS. An sslmode already in the URL is kept as given.
if [[ "$DB_URL" != *sslmode=* ]]; then
  if [[ "$DB_URL" == *"?"* ]]; then DB_URL="$DB_URL&sslmode=require"; else DB_URL="$DB_URL?sslmode=require"; fi
fi

# Credentials go into --env-file, never -e: that keeps them out of the process table and any
# command echo. They are NOT hidden from root on this instance: the env file is readable by
# root, and `podman inspect` shows the container's .Config.Env, credentials included.
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
# identity and parameters, plus the task's), runs the container, and publishes the results.
# A task is {runId, module?, reportsPrefix, logKey, env: {ETL_JOB, ETL_RUN_ID, ETL_PARAM_*}}.
run_task() {
  local task=$WORK/task.json
  local safe='^[A-Za-z0-9._-]+$' safe_key='^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$'
  if ! jq -e 'type == "object"' "$task" >/dev/null 2>&1; then
    say "ERROR: dropping a task that is not a JSON object"
    return 0
  fi
  TASK_RUN_ID=$(jq -r '.runId // empty | strings' "$task")
  TASK_REPORTS=$(jq -r '.reportsPrefix // empty | strings' "$task")
  TASK_LOG_KEY=$(jq -r '.logKey // empty | strings' "$task")
  TASK_JOB=$(jq -r '.env.ETL_JOB? // empty | strings' "$task" 2>/dev/null || true)
  TASK_MODULE=$(jq -r --arg d '${module_name}' '.module // $d | strings' "$task")
  TASK_STARTED=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  # Without a usable run id, reports prefix and log key there is nowhere safe to publish a result.
  if [[ ! "$TASK_RUN_ID" =~ $safe || ! "$TASK_REPORTS" =~ $safe_key || ! "$TASK_LOG_KEY" =~ $safe_key \
        || "$TASK_REPORTS" == *..* || "$TASK_LOG_KEY" == *..* ]]; then
    say "ERROR: dropping a task with a malformed run id, reports prefix or log key (no result can be published)"
    return 0
  fi

  TASK_ACTIVE=1
  rm -rf "$REPORTS" && mkdir -p "$REPORTS"
  : > "$WORK/task.log"
  # Anything else wrong is a config error, reported through the task's own sentinel and log.
  if [[ ! "$TASK_JOB" =~ $safe || ! "$TASK_MODULE" =~ $safe ]]; then
    [[ "$TASK_JOB" =~ $safe ]] || TASK_JOB=
    [[ "$TASK_MODULE" =~ $safe ]] || TASK_MODULE='${module_name}'
    tsay "ERROR: task $TASK_RUN_ID has a missing or malformed env.ETL_JOB or module"
    publish_task 5 task
    return 0
  fi
  tsay "Task $TASK_RUN_ID: running $TASK_JOB on $INSTANCE_ID"

  # Only the job identity and job parameters may come from a task, as single-line strings, and
  # its ETL_RUN_ID must be the run id it publishes under.
  local bad
  bad=$(jq -r '.runId as $run | .env | to_entries[]
    | select((.key | test("^(ETL_JOB|ETL_RUN_ID|ETL_PARAM_[A-Za-z0-9_]+)$") | not)
             or ((.value | type) != "string") or (.value | test("[\\r\\n]"))
             or (.key == "ETL_RUN_ID" and .value != $run))
    | .key' "$task" 2>/dev/null || echo "(unreadable env)")
  if [[ -n "$bad" ]]; then
    tsay "ERROR: task $TASK_RUN_ID has disallowed env entries (only ETL_JOB, ETL_RUN_ID = runId, and single-line ETL_PARAM_* strings): $(tr '\n' ' ' <<<"$bad")"
    publish_task 5 task
    return 0
  fi
  ( umask 077
    grep -v -E '^(ETL_JOB|ETL_RUN_ID|ETL_PARAM_[A-Za-z0-9_]*)=' "$ENV_FILE" > "$WORK/task.env" || true
    printf 'ETL_RUN_ID=%s\n' "$TASK_RUN_ID" >> "$WORK/task.env"
    jq -r '.env | to_entries[] | select(.key != "ETL_RUN_ID") | "\(.key)=\(.value)"' "$task" >> "$WORK/task.env" )

  PHASE=job
  set +e
  podman run --rm \
    --env-file "$WORK/task.env" \
    -v "$REPORTS":/reports:Z \
    "${image_name}" 2>&1 | tee -a "$WORK/task.log"
  local code=$${PIPESTATUS[0]}
  set -e
  PHASE=idle
  tsay "Task $TASK_RUN_ID: $TASK_JOB exited $code ($(exit_name "$code"))"
  publish_task "$code" done
}

say "Worker ready; waiting for tasks in $MAILBOX/ (idle limit ${pool_idle_timeout}s)"
IDLE_SINCE=$(date +%s)
while true; do
  if aws s3api head-object --bucket "${stack_s3_bucket}" --key "$POOL_PREFIX/$INSTANCE_ID/stop" \
       --region "${aws_region}" >/dev/null 2>&1; then
    say "Stop requested"
    PHASE=stopped
    JOB_EXIT=0
    exit 0
  fi
  if POLL_ERR=$(aws s3 cp "$MAILBOX/task.json" "$WORK/task.json" --region "${aws_region}" --only-show-errors 2>&1 >/dev/null); then
    # Claimed only once it is gone from the mailbox: left there, it would run again after this
    # run, and the dispatcher would refuse the next task as unclaimed. Until the delete succeeds
    # the task is not run; the next poll claims it again. This mailbox has a single reader.
    if ! aws s3 rm "$MAILBOX/task.json" --region "${aws_region}" --only-show-errors; then
      say "WARN: could not remove the claimed task from $MAILBOX/; not running it until that succeeds"
      rm -f "$WORK/task.json"
      sleep 10
      continue
    fi
    run_task
    rm -f "$WORK/task.json"
    IDLE_SINCE=$(date +%s)
    continue
  fi
  # An empty mailbox is a 404; anything else (permissions, throttling) is worth a log line.
  if [[ "$POLL_ERR" != *"(404)"* && "$POLL_ERR" != *"Not Found"* && "$POLL_ERR" != *NoSuchKey* ]]; then
    say "WARN: could not poll $MAILBOX/task.json: $(tr '\n' ' ' <<<"$POLL_ERR")"
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
PHASE=job

say "Running job ${job_name} (run ${run_id})"
checkpoint_loop &
CHECKPOINT_PID=$!
set +e
# :Z relabels the reports directory so the container may write it with SELinux enforcing.
podman run --rm \
  --env-file "$ENV_FILE" \
  -v "$REPORTS":/reports:Z \
  "${image_name}"
JOB_EXIT=$?
set -e
stop_checkpoints
say "Job ${job_name} exited $JOB_EXIT ($(exit_name "$JOB_EXIT"))"

PHASE=done
# The EXIT trap uploads artifacts and terminates the instance.
exit "$JOB_EXIT"
%{ endif ~}
