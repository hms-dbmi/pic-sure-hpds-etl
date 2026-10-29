provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# TEMPORARY bridge from the per-study allConcepts layout to the hpds-pheno-load csvloader-split input.
# Reads every file of the manifest by version id and builds the output on the runner's root volume before
# uploading it, so root_volume_size must hold the whole output.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region                = var.aws_region
  module_name               = "adapt-allconcepts-csvloader"
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

  job_name  = "adapt-allconcepts-csvloader"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  # Touches no database: uses_participant_db stays false, so this job runs whether or not the
  # participant database is up.

  job_params = merge(
    {
      manifest               = var.manifest_uri
      output                 = var.output_uri
      max_reject_fraction    = var.max_reject_fraction
      multi_study_ids        = var.multi_study_ids
      foreign_study_rows     = var.foreign_study_rows
      unmatched_consent_rows = var.unmatched_consent_rows
      zero_timestamps        = "blank"
    },
    var.studies != "" ? { studies = var.studies } : {},
    var.allow_consent_codes != "" ? { allow_consent_codes = var.allow_consent_codes } : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "temporary"
  }, var.tags)
}
