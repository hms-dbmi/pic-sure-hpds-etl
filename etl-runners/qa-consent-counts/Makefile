# PERMANENT QA: ephemeral runner for the qa-consent-counts job, the last stage of both pipelines.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required
#   TF_VAR_managed_inputs_uri   optional  managed inputs CSV; its ready studies are checked
#   TF_VAR_load_counts_uri      optional  this run's participants loaded per consent group
#   TF_VAR_study_filter, TF_VAR_concurrency, TF_VAR_dbgap_*   optional, see terraform/variables.tf
#
# Run locally:
#   export TF_VAR_run_id=local-1 TF_VAR_managed_inputs_uri=s3://bucket/managed_inputs.csv TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
#
#   make test   build-load-counts.py and validate.sh against fixtures (no AWS needed)
NAME := qa-consent-counts

include ../common.mk

.PHONY: preflight validate test

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"

test:
	@./test/run-tests.sh
