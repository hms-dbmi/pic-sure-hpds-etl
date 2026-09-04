# PERMANENT: ephemeral runner for the generate-identity-consent-mapping job.
NAME := generate-identity-consent-mapping

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
