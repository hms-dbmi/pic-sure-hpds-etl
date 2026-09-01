# TEMPORARY: ephemeral runner for the split-allconcepts job (JobType.MIGRATION).
# Delete alongside the job once the migration has run in every environment.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id        required
#   TF_VAR_study_id      required
#   TF_VAR_abbreviation  required
#   TF_VAR_input_uri     required
#   TF_VAR_mapping_uri   required
#   TF_VAR_output_uri    required
NAME := split-allconcepts

include ../common.mk

.PHONY: preflight validate

# Cheap checks against the input layout, run BEFORE any instance is provisioned.
preflight:
	@./preflight.sh

# Post-run assertions over the reports fetched by `make fetch-reports`.
validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
