#!/bin/bash
#
# Pre-flight checks for the qa-consent-counts job, run on the Jenkins agent BEFORE any EC2
# instance is provisioned.
#
# Environment:
#   TF_VAR_managed_inputs_uri   optional   managed inputs CSV (blank: the job's configured default)
#   TF_VAR_load_counts_uri      optional   this run's load counts CSV (blank: no load-report check)
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
LOAD_COUNTS="$(trim "${TF_VAR_load_counts_uri:-}")"
DBGAP="$(trim "${TF_VAR_dbgap_base_url:-}")"
DBGAP="${DBGAP:-https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study}"

echo "Pre-flight: qa-consent-counts"
echo "  managed inputs: ${MANAGED_INPUTS:-<configured default>}"
echo "  load counts:    ${LOAD_COUNTS:-<none: DB vs dbGaP only>}"
echo "  dbGaP:          $DBGAP"
echo ""

exists() {   # <uri>: an s3:// object or a local file
  if [[ "$1" == s3://* ]]; then
    aws s3 ls "$1" --region "$REGION" >/dev/null 2>&1
  else
    test -f "$1"
  fi
}

if [[ -n "$MANAGED_INPUTS" ]]; then
  check "managed inputs exist: $MANAGED_INPUTS" exists "$MANAGED_INPUTS"
else
  warn "MANAGED_INPUTS is blank: the job falls back to its configured etl.managed-inputs.uri"
fi

if [[ -n "$LOAD_COUNTS" ]]; then
  check "load counts exist: $LOAD_COUNTS" exists "$LOAD_COUNTS"
fi

if [[ "$DBGAP" != http://* && "$DBGAP" != https://* ]]; then
  fail "DBGAP_BASE_URL must be an http(s) URL, got: $DBGAP"
elif command -v curl >/dev/null 2>&1; then
  # Any HTTP answer (an unknown study is a 400) shows the API is up. This is the agent's network,
  # not the runner's: the runner's security group needs its own HTTPS egress to the API.
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$DBGAP/phs000001.v1/summary" 2>/dev/null || true)
  soft "dbGaP API answers from the Jenkins agent (HTTP ${code:-none}; the job retries an outage, then exits 4)" \
    test "${code:-000}" != 000 -a "${code:-000}" -lt 500
fi

summary
