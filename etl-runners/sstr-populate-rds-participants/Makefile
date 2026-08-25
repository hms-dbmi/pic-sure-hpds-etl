# PERMANENT: ephemeral runner for the sstr-populate-rds-participants job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id      required
#   TF_VAR_study_id    required  phs######
#   TF_VAR_input_uri   required  the SSTR TSV
#   TF_VAR_batch_size  optional  (default 1000)
#   TF_VAR_name_suffix optional  must be unique per study when loading several at once
#
# Load one study locally:
#   export TF_VAR_run_id=local-1 TF_VAR_study_id=phs001412 \
#          TF_VAR_input_uri=s3://.../phs001412.sstr.txt TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
NAME := sstr-populate-rds-participants

include ../common.mk

.PHONY: preflight validate

# Cheap checks against the input file, run BEFORE any instance is provisioned.
preflight:
	@./preflight.sh

# Post-run assertions over the report fetched by `make fetch-reports`.
validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
