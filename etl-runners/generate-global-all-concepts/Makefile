# PERMANENT: ephemeral runner for the generate-global-all-concepts job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id      required
#   TF_VAR_output_uri  required  where global_AllConcepts.csv is written
#
# Generate locally:
#   export TF_VAR_run_id=local-1 TF_VAR_output_uri=./output/ TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
NAME := generate-global-all-concepts

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
