provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT QA. Counts the participants per consent group of every ready study in the participant
# database (and this run's load reports) and compares them with dbGaP's subject counts for that
# study version, read from the dbGaP SSTR API. Read-only. The runner's security group must allow
# HTTPS (443) egress to www.ncbi.nlm.nih.gov, besides the participant DB and S3.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region                = var.aws_region
  module_name               = "qa-consent-counts"
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

  job_name  = "qa-consent-counts"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  uses_participant_db = true
  environment         = var.environment

  # Blank values are left out, so the job applies its own defaults.
  job_params = { for k, v in {
    managed_inputs                = var.managed_inputs_uri
    load_counts                   = var.load_counts_uri
    study_filter                  = var.study_filter
    concurrency                   = var.concurrency
    dbgap_base_url                = var.dbgap_base_url
    dbgap_max_attempts            = var.dbgap_max_attempts
    dbgap_initial_backoff_seconds = var.dbgap_initial_backoff_seconds
    dbgap_max_backoff_seconds     = var.dbgap_max_backoff_seconds
    dbgap_timeout_seconds         = var.dbgap_timeout_seconds
    dbgap_give_up_after           = var.dbgap_give_up_after
    dbgap_deadline_seconds        = var.dbgap_deadline_seconds
  } : k => v if v != "" }

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
