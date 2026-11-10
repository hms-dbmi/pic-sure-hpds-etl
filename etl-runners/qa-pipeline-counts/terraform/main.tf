provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT QA, the last step of both pipelines. Compares every ready study's counts per consent
# group (participant DB, and this run's job and final reports) with its dbGaP study summary, read
# from {data_root}/{study}/rawData/{study}_{version}_summary.json or fetched from the dbGaP SSTR API
# and cached there. Writes only those summaries. The runner's security group must allow HTTPS (443)
# egress to www.ncbi.nlm.nih.gov, besides the participant DB and S3.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region                = var.aws_region
  module_name               = "qa-pipeline-counts"
  name_suffix               = var.name_suffix
  stack_s3_bucket           = var.stack_s3_bucket
  ami_owner_id              = var.ami_owner_id
  ami_name_pattern          = var.ami_name_pattern
  instance_type             = var.instance_type
  vpc_id                    = var.vpc_id
  subnet_id                 = var.subnet_id
  vpc_security_group_ids    = var.vpc_security_group_ids
  iam_role_name             = var.iam_role_name
  iam_instance_profile_name = var.iam_instance_profile_name
  root_volume_size          = var.root_volume_size

  job_name  = "qa-pipeline-counts"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  uses_participant_db = true
  environment         = var.environment

  # Blank values are left out, so the job applies its own defaults.
  job_params = { for k, v in {
    managed_inputs                  = var.managed_inputs_uri
    data_root                       = var.data_root_uri
    run_counts                      = var.run_counts_uri
    pipeline                        = var.pipeline
    study_filter                    = var.study_filter
    cache_concurrency               = var.cache_concurrency
    cache_summaries                 = var.cache_summaries
    concurrency                     = var.concurrency
    dbgap_base_url                  = var.dbgap_base_url
    dbgap_max_attempts              = var.dbgap_max_attempts
    dbgap_initial_backoff_seconds   = var.dbgap_initial_backoff_seconds
    dbgap_max_backoff_seconds       = var.dbgap_max_backoff_seconds
    dbgap_timeout_seconds           = var.dbgap_timeout_seconds
    dbgap_give_up_after             = var.dbgap_give_up_after
    dbgap_unreachable_pause_seconds = var.dbgap_unreachable_pause_seconds
    dbgap_request_spacing_seconds   = var.dbgap_request_spacing_seconds
    dbgap_deadline_seconds          = var.dbgap_deadline_seconds
  } : k => v if v != "" }

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "both"
  }, var.tags)
}
