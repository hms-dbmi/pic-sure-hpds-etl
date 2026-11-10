# PERMANENT QA: ephemeral runner for the qa-pipeline-counts job, the last step of both pipelines.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required
#   TF_VAR_managed_inputs_uri   required  managed inputs CSV; every ready study is checked
#   TF_VAR_data_root_uri        required  per-study folders; summaries cached under {root}/{study}/rawData/
#   TF_VAR_run_counts_uri       optional  this run's counts per consent group (collect-run-counts.py)
#   TF_VAR_pipeline, TF_VAR_study_filter, TF_VAR_cache_*, TF_VAR_concurrency, TF_VAR_dbgap_*
#                               optional, see terraform/variables.tf
#
# Run locally:
#   export TF_VAR_run_id=local-1 TF_VAR_managed_inputs_uri=s3://bucket/managed_inputs.csv \
#          TF_VAR_data_root_uri=s3://bucket/root TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
#
#   make test   validate.sh, preflight.sh and collect-run-counts.py against fixtures (no AWS needed)
NAME := qa-pipeline-counts

include ../common.mk

.PHONY: preflight validate test

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"

test:
	@./test/run-tests.sh
