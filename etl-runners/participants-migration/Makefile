# TEMPORARY: ephemeral runner for the participants-migration job (JobType.MIGRATION).
# Delete alongside the job once the migration has run in every environment.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id             required
#   TF_VAR_managed_inputs_uri required
#   TF_VAR_data_folder_uri    required
#   TF_VAR_batch_size         optional (default 1000)
#   TF_VAR_name_suffix        optional (Jenkins BUILD_NUMBER)
NAME := participants-migration

include ../common.mk

.PHONY: preflight validate

# Cheap checks against the input layout, run BEFORE any instance is provisioned.
preflight:
	@./preflight.sh

# Post-run assertions over the reports fetched by `make fetch-reports`.
validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
