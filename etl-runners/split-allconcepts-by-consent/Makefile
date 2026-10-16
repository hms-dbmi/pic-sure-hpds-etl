# PERMANENT: ephemeral runner for the split-allconcepts-by-consent job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id        required
#   TF_VAR_input_uri     required   the merged allConcepts CSV
#   TF_VAR_output_uri    required   output prefix (never under avillach-73-bdcatalyst-etl)
#   TF_VAR_depth         optional   default 1
#   TF_VAR_globals       optional   own (default) | by-patient
#   TF_VAR_fence_mapping optional   default is the job's own default; "none" skips the cross-check
#   TF_VAR_force         optional   default false
NAME := split-allconcepts-by-consent

include ../common.mk

.PHONY: preflight validate test

# Cheap checks against the input and output, run BEFORE any instance is provisioned.
preflight:
	@./preflight.sh

# Post-run assertions over the reports fetched by `make fetch-reports`.
validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"

# Shell tests for preflight.sh and validate.sh (aws is stubbed; no AWS access needed).
test:
	@./test/run-tests.sh
