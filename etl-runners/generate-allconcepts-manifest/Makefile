# PERMANENT: ephemeral runner for the generate-allconcepts-manifest job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required
#   TF_VAR_input_uri            required  data root (versioned s3://) with {study_id}/allConcepts/c{consent}/
#   TF_VAR_global_allconcepts_uri required  global_AllConcepts.csv (or its folder); listed first
#   TF_VAR_managed_inputs_uri   optional  managed inputs CSV; its ready studies are listed
#
# Generate locally:
#   export TF_VAR_run_id=local-1 TF_VAR_input_uri=s3://bucket/data/ TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
NAME := generate-allconcepts-manifest

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
