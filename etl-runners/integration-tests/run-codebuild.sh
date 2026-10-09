#!/bin/bash
#
# Runs `./mvnw verify` (unit + Testcontainers *IT suites) for the checked-out commit in AWS
# CodeBuild, waits for it, and brings the JUnit reports back to target/ so the Jenkins `junit`
# step reads them as if the suites had run locally. The agent needs no container runtime.
#
#   run-codebuild.sh <env> <run-tag>
#
#   <env>       selects etl-runners/environments/<env>.tfvars (region, bucket)
#   <run-tag>   unique per run (Jenkins BUILD_TAG); names the S3 prefix
#
# Environment:
#   CODEBUILD_PROJECT           default hpds-etl-integration-tests (etl-runners/integration-tests/terraform)
#   CODEBUILD_TIMEOUT_SECONDS   default 4500; the project's own timeout is 60 minutes
#
# The source is `git archive HEAD`: committed content only, exactly what Jenkins checked out.
#
# Exit: 0 build SUCCEEDED | 1 anything else (test failures, build error, timeout, stopped)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

ENV_NAME="${1:?usage: run-codebuild.sh <env> <run-tag>}"
TAG="$(printf '%s' "${2:?usage: run-codebuild.sh <env> <run-tag>}" | tr -c 'A-Za-z0-9._-' '-')"
PROJECT="${CODEBUILD_PROJECT:-hpds-etl-integration-tests}"
TIMEOUT="${CODEBUILD_TIMEOUT_SECONDS:-4500}"

ENV_TFVARS="$REPO_ROOT/etl-runners/environments/$ENV_NAME.tfvars"
tfvar() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\\(.*\\)\"/\\1/p" "$ENV_TFVARS"; }
REGION="$(tfvar aws_region)"
BUCKET="$(tfvar stack_s3_bucket)"
[[ -n "$REGION" && -n "$BUCKET" ]] || { echo "ERROR: aws_region/stack_s3_bucket not found in $ENV_TFVARS" >&2; exit 1; }

PREFIX="etl-runner/codebuild/$TAG"
BUILD_ID=""

cleanup() {
  aws s3 rm "s3://$BUCKET/$PREFIX/" --recursive --region "$REGION" --only-show-errors || true
}

# An aborted Jenkins build must not leave a CodeBuild build running (and billing) behind.
on_abort() {
  if [[ -n "$BUILD_ID" ]]; then
    echo "Aborted: stopping $BUILD_ID"
    aws codebuild stop-build --id "$BUILD_ID" --region "$REGION" >/dev/null || true
  fi
  cleanup
  exit 1
}
trap on_abort INT TERM

echo "--- Integration tests in CodeBuild ($PROJECT, $REGION) ---"

SRC_ZIP="$(mktemp)"
git -C "$REPO_ROOT" archive --format=zip -o "$SRC_ZIP" HEAD
aws s3 cp "$SRC_ZIP" "s3://$BUCKET/$PREFIX/source.zip" --region "$REGION" --no-progress
rm -f "$SRC_ZIP"

BUILD_ID="$(aws codebuild start-build --region "$REGION" \
  --project-name "$PROJECT" \
  --source-type-override S3 \
  --source-location-override "$BUCKET/$PREFIX/source.zip" \
  --artifacts-override "type=S3,location=$BUCKET,path=$PREFIX,name=reports,packaging=NONE,namespaceType=NONE" \
  --query 'build.id' --output text)"
echo "Started $BUILD_ID"

deadline=$((SECONDS + TIMEOUT))
last_phase=""
while :; do
  if (( SECONDS > deadline )); then
    echo "Timed out after ${TIMEOUT}s; stopping $BUILD_ID"
    aws codebuild stop-build --id "$BUILD_ID" --region "$REGION" >/dev/null || true
    status="TIMED_OUT"
    break
  fi
  # A transient API error must not end the wait (and orphan the build): retry next tick.
  if ! read -r status phase < <(aws codebuild batch-get-builds --ids "$BUILD_ID" --region "$REGION" \
      --query 'builds[0].[buildStatus,currentPhase]' --output text); then
    echo "  (status check failed; retrying)"
    sleep 20
    continue
  fi
  if [[ "$phase" != "$last_phase" ]]; then
    echo "  phase: $phase"
    last_phase="$phase"
  fi
  [[ "$status" != "IN_PROGRESS" ]] && break
  sleep 20
done

read -r log_group log_stream deep_link < <(aws codebuild batch-get-builds --ids "$BUILD_ID" --region "$REGION" \
  --query 'builds[0].logs.[groupName,streamName,deepLink]' --output text)
echo "Build $status. Full log: $deep_link"

if [[ "$status" != "SUCCEEDED" && -n "$log_stream" && "$log_stream" != "None" ]]; then
  echo "--- last 200 log lines ---"
  aws logs get-log-events --region "$REGION" \
    --log-group-name "$log_group" --log-stream-name "$log_stream" \
    --limit 200 --query 'events[].message' --output json | jq -r '.[]' | sed 's/\r$//; /^$/d'
  echo "--------------------------"
fi

# Reports land under target/ exactly where the junit step looks for them.
rm -rf "$REPO_ROOT/target/surefire-reports" "$REPO_ROOT/target/failsafe-reports"
aws s3 sync "s3://$BUCKET/$PREFIX/reports/" "$REPO_ROOT" --region "$REGION" --no-progress \
  || echo "WARN: no test reports were uploaded"
cleanup

[[ "$status" == "SUCCEEDED" ]]
