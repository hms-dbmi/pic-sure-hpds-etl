# PERMANENT: ephemeral runner for the create-vcf-indexes job.
NAME := create-vcf-indexes

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
