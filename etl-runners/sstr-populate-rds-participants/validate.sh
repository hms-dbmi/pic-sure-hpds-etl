#!/bin/bash
#
# Post-run validation for sstr-populate-rds-participants, over the report fetched from the
# runner's S3 report prefix.
#
# The job's metrics are counts of rows the repositories actually affected in RDS, which makes
# two of them exact invariants rather than heuristics:
#
#   consentsWritten == distinctParticipants
#       One consent row is built per distinct dbgap_subject_id, and ConsentRepository upserts
#       with ON CONFLICT DO UPDATE, so every row reports 1 affected. Any other number means
#       rows went missing between the file and the table.
#
#   sum(countsByConsentGroup) == distinctParticipants
#       The same one-row-per-subject set, grouped by CONSENT. A shortfall means subjects were
#       counted into no group at all.
#
# participantsInserted is NOT such an invariant: ParticipantRepository upserts with ON
# CONFLICT DO NOTHING, so it counts only NEW participants and is legitimately 0 when every
# subject was already known.
#
# Usage: validate.sh <reports-dir> <run-id>
# Environment (optional, from studies.tsv):
#   EXPECTED_consent_codeS    comma-separated CONSENT values this study must produce
#   EXPECTED_MIN_PARTICIPANTS  floor on distinct participants
# Exit: 0 clean | 10 clean but with warnings | 1 failed | 64 usage
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../common/lib.sh
source "$HERE/../common/lib.sh"

REPORTS="${1:-}"
RUN_ID="${2:-}"
if [[ -z "$REPORTS" || -z "$RUN_ID" ]]; then
  echo "usage: $0 <reports-dir> <run-id>" >&2
  exit 64
fi

REPORT="$REPORTS/sstr-populate-rds-participants-$RUN_ID.json"
EXPECTED_GROUPS="${EXPECTED_consent_codeS:-}"
EXPECTED_MIN="${EXPECTED_MIN_PARTICIPANTS:-}"

echo "=========================================================="
echo " sstr-populate-rds-participants validation (run $RUN_ID)"
echo "=========================================================="

"$HERE/../common/validate-report.sh" "$REPORT"
GENERIC_RC=$?
case "$GENERIC_RC" in
  0)  echo "  [ok]   standard report checks"; _PASS=$((_PASS + 1)) ;;
  10) echo "  [warn] standard report checks (warnings above)"; _WARN=$((_WARN + 1)) ;;
  *)  echo "  [FAIL] standard report checks"; _FAIL=$((_FAIL + 1)) ;;
esac

if [[ ! -f "$REPORT" ]]; then
  summary
  exit 1
fi

ROWS=$(jq -r      '.metrics.rowsRead             // 0' "$REPORT")
SUBJECTS=$(jq -r  '.metrics.distinctParticipants // 0' "$REPORT")
NEW=$(jq -r       '.metrics.participantsInserted // 0' "$REPORT")
CONSENTS=$(jq -r  '.metrics.consentsWritten      // 0' "$REPORT")
SAMPLES=$(jq -r   '.metrics.samplesInserted      // 0' "$REPORT")
GROUP_SUM=$(jq -r '[(.metrics.countsByConsentGroup // {})[]] | add // 0' "$REPORT")
GROUP_KEYS=$(jq -r '(.metrics.countsByConsentGroup // {}) | keys_unsorted | join(",")' "$REPORT")

echo ""
echo "  rowsRead=$ROWS  distinctParticipants=$SUBJECTS  newParticipants=$NEW"
echo "  consentsWritten=$CONSENTS  samplesInserted=$SAMPLES"
echo "  consent groups: ${GROUP_KEYS:-<none>}"

# --- shape of the load ---------------------------------------------------
check "read at least one data row"                       test "$ROWS" -gt 0
check "found at least one distinct participant"          test "$SUBJECTS" -gt 0
check "no more participants than rows"                   test "$SUBJECTS" -le "$ROWS"

# --- the two invariants --------------------------------------------------
check "consentsWritten == distinctParticipants ($CONSENTS == $SUBJECTS)" \
      test "$CONSENTS" -eq "$SUBJECTS"
check "consent-group counts sum to distinctParticipants ($GROUP_SUM == $SUBJECTS)" \
      test "$GROUP_SUM" -eq "$SUBJECTS"

# --- idempotency ---------------------------------------------------------
check "no more new participants than distinct subjects"  test "$NEW" -le "$SUBJECTS"
if [[ "$NEW" == "0" && "$SUBJECTS" != "0" ]]; then
  # Expected on a reload; suspicious on a study's first load.
  warn "0 new participants: every subject already existed. Correct for a reload, wrong for a first load."
fi

# One sample row per input row that has a non-blank dbgap_sample_id, deduplicated by the
# (uuid, sample id, source) constraint -- so at most rowsRead.
check "no more samples than rows"                        test "$SAMPLES" -le "$ROWS"
if [[ "$SAMPLES" == "0" ]]; then
  warn "no sample rows written: every dbgap_sample_id in the file was blank"
fi

# --- consent groups ------------------------------------------------------
check "at least one consent group" test -n "$GROUP_KEYS"

# The job records one consent_code_COUNT info issue per group; a mismatch means the report's
# two accounts of the same fact disagree.
GROUP_COUNT=$(jq -r '(.metrics.countsByConsentGroup // {}) | length' "$REPORT")
ISSUE_COUNT=$(jq -r '[(.outputValidation.issues // [])[] | select(.code == "consent_code_COUNT")] | length' "$REPORT")
check "one consent_code_COUNT record per group ($ISSUE_COUNT == $GROUP_COUNT)" \
      test "$ISSUE_COUNT" -eq "$GROUP_COUNT"

if [[ -n "$EXPECTED_GROUPS" ]]; then
  want=$(tr ',' '\n' <<<"$EXPECTED_GROUPS" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | sort -u | paste -sd, -)
  got=$(tr ',' '\n' <<<"$GROUP_KEYS" | sort -u | paste -sd, -)
  check "consent groups are exactly the expected set (want: $want)" test "$got" = "$want"
  [[ "$got" != "$want" ]] && note "got: $got"
else
  note "no EXPECTED_consent_codeS declared for this study -- group membership is unverified"
fi

if [[ -n "$EXPECTED_MIN" ]]; then
  check "at least $EXPECTED_MIN participant(s) loaded" test "$SUBJECTS" -ge "$EXPECTED_MIN"
else
  note "no EXPECTED_MIN_PARTICIPANTS declared for this study -- volume is unverified"
fi

echo ""
echo "  Participants per consent group:"
jq -r '(.metrics.countsByConsentGroup // {}) | to_entries[] | "         \(.key): \(.value)"' "$REPORT"

# Traceability: confirm the report belongs to the expected invocation.
CONTAINER_STATUS="$REPORTS/container-status.json"
if [[ -f "$CONTAINER_STATUS" ]]; then
  echo ""
  note "invoked as: $(jq -r '.args' "$CONTAINER_STATUS")"
  soft "container-status agrees the job succeeded" jq -e '.exitCode == 0' "$CONTAINER_STATUS"
else
  warn "container-status.json missing: cannot confirm which arguments produced this report"
fi

summary
