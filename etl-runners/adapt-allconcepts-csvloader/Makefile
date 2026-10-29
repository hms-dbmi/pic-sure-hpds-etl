# TEMPORARY: ephemeral runner for the adapt-allconcepts-csvloader job (a bridge from the per-study
# allConcepts layout to the hpds-pheno-load csvloader-split input; see docs/ADAPT_ALLCONCEPTS_CSVLOADER.md).
#
# Job parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required
#   TF_VAR_manifest_uri         required  s3:// URI of allConcepts_manifest.csv (versioned bucket)
#   TF_VAR_output_uri           required  EMPTY s3:// prefix, never under avillach-73-bdcatalyst-etl
#   TF_VAR_studies              optional  comma-separated study ids
#   TF_VAR_max_reject_fraction  optional  default 0
#   TF_VAR_allow_consent_codes  optional  comma-separated consent codes
#   TF_VAR_multi_study_ids      optional  copy (default) | refuse
#   TF_VAR_foreign_study_rows   optional  reject (default) | drop (stopgap: rows under another study's tree)
#   TF_VAR_unmatched_consent_rows optional reject (default) | drop (stopgap: _consents naming no partition)
#   TF_VAR_root_volume_size     optional  GiB, must hold the whole output
#
# Run locally:
#   export TF_VAR_run_id=local-1 TF_VAR_manifest_uri=s3://bucket/data/allConcepts_manifest.csv \
#          TF_VAR_output_uri=s3://bucket/data/transforms/adapted/local-1/ TF_VAR_name_suffix=local1
#   make preflight package run fetch-reports validate
#
# `make test` runs the contract tests (no AWS, no network).
NAME := adapt-allconcepts-csvloader

include ../common.mk

.PHONY: preflight validate test

preflight:
	@./preflight.sh

validate:
	@./validate.sh $(REPORTS_DIR) "$(TF_VAR_run_id)"

test:
	@./test/run-tests.sh
