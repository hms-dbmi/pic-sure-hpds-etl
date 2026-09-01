# PERMANENT: ephemeral runner for the all-concepts-data-generator job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id        required
#   TF_VAR_study_id      required  dbGaP study id
#   TF_VAR_data_dir      required  decoded data CSVs location
#   TF_VAR_mapping_uri   required  mapping CSV location
#   TF_VAR_output_uri    required  output directory for per-consent files
#   TF_VAR_skip_analysis optional  skip data type re-analysis (default false)
#
# Generate locally:
#   export TF_VAR_run_id=local-1 TF_VAR_study_id=phs001412 \
#     TF_VAR_data_dir=./data/ TF_VAR_mapping_uri=./data/mapping2.csv \
#     TF_VAR_output_uri=./output/ TF_VAR_name_suffix=local1
#   make package && make run && make fetch-reports && make validate
NAME := all-concepts-data-generator

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
