# PERMANENT: ephemeral runner for the merge-allconcepts job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id      required
#   TF_VAR_input_uri   required  S3 prefix with {study_id}/c{consent}/ folders
#
# Generate locally:
#   export TF_VAR_run_id=local-1 TF_VAR_input_uri=s3://bucket/output/ TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
NAME := merge-allconcepts

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
