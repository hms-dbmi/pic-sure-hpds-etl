#!/bin/bash
# Shared assertion helpers for runner pre-flight and post-run validation.
# Source this file; do not execute it.
#
#   check <label> <command...>   record pass/fail, keep going
#   soft  <label> <command...>   record pass/warn, keep going
#   fail  <message>              record an outright failure
#   warn  <message>              record a warning
#   note  <message>              informational line
#   summary                      print the tally; return 0 / 10 (warnings) / 1
#
# Commands are invoked directly (no eval), so express conditions as commands:
#   check "row count is positive"  test "$ROWS" -gt 0
#   check "status is SUCCESS"      jq -e '.status == "SUCCESS"' "$REPORT"
#
# Nothing aborts on the first failure, so one console read shows every problem with a run.
set -uo pipefail

_PASS=0
_FAIL=0
_WARN=0

note() { echo "         $*"; }

check() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "  [ok]   $label"
    _PASS=$((_PASS + 1))
  else
    echo "  [FAIL] $label"
    _FAIL=$((_FAIL + 1))
  fi
}

soft() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "  [ok]   $label"
    _PASS=$((_PASS + 1))
  else
    echo "  [warn] $label"
    _WARN=$((_WARN + 1))
  fi
}

fail() {
  echo "  [FAIL] $*"
  _FAIL=$((_FAIL + 1))
}

warn() {
  echo "  [warn] $*"
  _WARN=$((_WARN + 1))
}

summary() {
  echo ""
  echo "  ${_PASS} passed, ${_WARN} warning(s), ${_FAIL} failure(s)"
  if (( _FAIL > 0 )); then
    return 1
  elif (( _WARN > 0 )); then
    return 10   # callers map this to Jenkins UNSTABLE
  fi
  return 0
}
