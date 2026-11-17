#!/bin/bash
#
# Pre-flight checks for the qa-consent-counts job, run on the Jenkins agent BEFORE any EC2
# instance is provisioned.
#
# Environment:
#   TF_VAR_managed_inputs_uri   one of     managed inputs CSV whose ready studies are checked
#   TF_VAR_studies              these two  comma-separated study versions, e.g. phs000956.v6,phs001412.v4
#   TF_VAR_load_counts_uri      optional   a run's load counts CSV (blank: no load-report check)
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
STUDIES="$(trim "${TF_VAR_studies:-}")"
LOAD_COUNTS="$(trim "${TF_VAR_load_counts_uri:-}")"
DBGAP="$(trim "${TF_VAR_dbgap_base_url:-}")"
DBGAP="${DBGAP:-https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study}"

echo "Pre-flight: qa-consent-counts"
echo "  managed inputs: ${MANAGED_INPUTS:-<none>}"
echo "  studies:        ${STUDIES:-<none>}"
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

if [[ -n "$MANAGED_INPUTS" && -n "$STUDIES" ]]; then
  fail "give MANAGED_INPUTS or STUDIES, not both"
elif [[ -n "$MANAGED_INPUTS" ]]; then
  check "managed inputs exist: $MANAGED_INPUTS" exists "$MANAGED_INPUTS"
elif [[ -n "$STUDIES" ]]; then
  # The job validates them too; this fails before an instance is provisioned.
  BAD=""
  for s in ${STUDIES//[,;]/ }; do
    [[ "$s" =~ ^[Pp][Hh][Ss][0-9]+\.[Vv][0-9]+(\.[Pp][0-9]+)?$ ]] || BAD="$BAD $s"
  done
  check "STUDIES are study versions like phs000956.v6${BAD:+ (not:$BAD)}" test -z "$BAD"
else
  fail "give MANAGED_INPUTS (a managed inputs CSV) or STUDIES (study versions, e.g. phs000956.v6)"
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
