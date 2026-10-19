# PERMANENT: ephemeral runner for the merge-allconcepts job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required
#   TF_VAR_input_uri            required  S3 prefix (versioned) with {study_id}/allConcepts/c{consent}/ folders
#   TF_VAR_managed_inputs_uri   optional  managed inputs CSV; its ready studies are merged
#   TF_VAR_study_ids            optional  comma-separated phs###### to merge instead of the ready studies
#   TF_VAR_parallelism          optional  consent folders merged at once (default 8)
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
