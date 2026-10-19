# PERMANENT: ephemeral runner for the all-concepts-data-generator job.
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id        required
#   TF_VAR_study_id      required  dbGaP study id
#   TF_VAR_data_dir      required  decoded data CSVs location
#   TF_VAR_mapping_uri   required  mapping CSV location
#   TF_VAR_output_uri    required  s3:// output prefix on a versioned bucket
#   TF_VAR_skip_analysis optional  skip data type re-analysis (default false)
#
# Generate locally:
#   export TF_VAR_run_id=local-1 TF_VAR_study_id=phs001412 \
#     TF_VAR_data_dir=s3://.../BAM_testing/phs001412/decoded_data/ \
#     TF_VAR_mapping_uri=s3://.../BAM_testing/phs001412/mappings/mapping2.csv \
#     TF_VAR_output_uri=s3://.../BAM_testing/ TF_VAR_name_suffix=local1
#   (the orchestrator's layout: {DATA_ROOT}/{study_id}/decoded_data/ and
#   {DATA_ROOT}/{study_id}/mappings/mapping2.csv, with DATA_ROOT as the output root)
#   make package && make run && make fetch-reports && make validate
NAME := all-concepts-data-generator

include ../common.mk

.PHONY: preflight validate

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"
