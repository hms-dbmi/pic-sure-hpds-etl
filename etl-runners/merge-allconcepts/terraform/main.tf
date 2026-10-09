provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT. Merges per-consent allConcepts files into a single MERGED file per
# study/consent folder. Checks for missing, stale, or deletion-affected merged
# files using S3 object versioning, then concatenates source files as needed.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "merge-allconcepts"
  name_suffix      = var.name_suffix
  stack_s3_bucket  = var.stack_s3_bucket
  ami_owner_id     = var.ami_owner_id
  ami_name_pattern = var.ami_name_pattern
  instance_type    = var.instance_type
  vpc_id                 = var.vpc_id
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = var.iam_role_name
  root_volume_size = var.root_volume_size

  job_name  = "merge-allconcepts"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  # Touches no database: blank skips the credential fetch, so this job runs whether or
  # not the participant database is up.
  db_secret_id = ""

  job_params = merge(
    { input = var.input_uri },
    var.study_ids != "" ? { study_ids = var.study_ids } : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
