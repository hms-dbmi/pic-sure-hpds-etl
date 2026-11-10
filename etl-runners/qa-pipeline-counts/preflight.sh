#!/bin/bash
#
# Pre-flight checks for the qa-pipeline-counts job, run on the Jenkins agent BEFORE any EC2
# instance is provisioned.
#
# Environment:
#   TF_VAR_managed_inputs_uri   required   managed inputs CSV whose ready studies are checked
#   TF_VAR_data_root_uri        required   s3:// root of the per-study folders (the summary cache)
#   TF_VAR_run_counts_uri       optional   this run's counts per consent group (blank: DB vs dbGaP only)
#   TF_VAR_pipeline             optional   migration or permanent
#   TF_VAR_dbgap_base_url       optional   dbGaP SSTR study endpoint (blank: the job's default)
#   AWS_REGION                  optional   default us-east-1
#
# Exit: 0 clean | 10 clean but with warnings | 1 problems found
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REGION="${AWS_REGION:-us-east-1}"
MANAGED_INPUTS="$(trim "${TF_VAR_managed_inputs_uri:-}")"
DATA_ROOT="$(trim "${TF_VAR_data_root_uri:-}")"
RUN_COUNTS="$(trim "${TF_VAR_run_counts_uri:-}")"
PIPELINE="$(trim "${TF_VAR_pipeline:-}")"
DBGAP="$(trim "${TF_VAR_dbgap_base_url:-}")"
DBGAP="${DBGAP:-https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study}"

echo "Pre-flight: qa-pipeline-counts"
echo "  managed inputs: ${MANAGED_INPUTS:-<none>}"
echo "  data root:      ${DATA_ROOT:-<none>}"
echo "  run counts:     ${RUN_COUNTS:-<none: DB vs dbGaP only>}"
echo "  pipeline:       ${PIPELINE:-<none>}"
echo "  dbGaP:          $DBGAP"
echo ""

exists() {   # <uri>: an s3:// object or a local file
  if [[ "$1" == s3://* ]]; then
    aws s3 ls "$1" --region "$REGION" >/dev/null 2>&1
  else
    test -f "$1"
  fi
}

if [[ -z "$MANAGED_INPUTS" ]]; then
  fail "MANAGED_INPUTS (a managed inputs CSV) is required"
else
  check "managed inputs exist: $MANAGED_INPUTS" exists "$MANAGED_INPUTS"
fi
if [[ -z "$DATA_ROOT" ]]; then
  fail "DATA_ROOT (the per-study folders, where summaries are cached) is required"
else
  check "DATA_ROOT is an s3:// URI: $DATA_ROOT" test "${DATA_ROOT#s3://}" != "$DATA_ROOT"
fi
if [[ -n "$RUN_COUNTS" ]]; then
  check "run counts exist: $RUN_COUNTS" exists "$RUN_COUNTS"
fi
if [[ -n "$PIPELINE" ]]; then
  check "PIPELINE is migration or permanent ($PIPELINE)" test "$PIPELINE" = migration -o "$PIPELINE" = permanent
fi

if [[ "$DBGAP" != http://* && "$DBGAP" != https://* ]]; then
  fail "DBGAP_BASE_URL must be an http(s) URL, got: $DBGAP"
elif command -v curl >/dev/null 2>&1; then
  # Any HTTP answer (an unknown study is a 400) shows the API is up. Only summaries not yet cached
  # need it, and the job waits out an outage up to its deadline, so this is only a warning. This is
  # the agent's network, not the runner's: the runner's security group needs its own HTTPS egress.
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$DBGAP/phs000001.v1/summary" 2>/dev/null || true)
  soft "dbGaP API answers from the Jenkins agent (HTTP ${code:-none}; needed only for summaries not yet cached)" \
    test "${code:-000}" != 000 -a "${code:-000}" -lt 500
fi

summary
