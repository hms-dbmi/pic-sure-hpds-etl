provider "aws" {
  region = var.aws_region
}

# TEMPORARY. Runs the one-off split-allconcepts job (JobType.MIGRATION) on a
# self-terminating EC2 instance. Delete this directory together with the job once
# the migration has run in every environment.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "split-allconcepts"
  name_suffix      = var.name_suffix
  stack_s3_bucket  = var.stack_s3_bucket
  ami_owner_id     = var.ami_owner_id
  ami_name_pattern = var.ami_name_pattern
  instance_type    = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = "jenkins-s3-role"
  root_volume_size = var.root_volume_size

  job_name  = "split-allconcepts"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  rds_secret_id        = var.rds_secret_id
  rds_secret_arn       = var.rds_secret_arn
  manage_secret_access = var.manage_secret_access

  # Keys use underscores; the runner converts them to --study-id, --abbreviation, etc.
  # Names must match SplitAllConceptsJob.expectations().
  job_params = {
    study_id     = var.study_id
    abbreviation = var.abbreviation
    input        = var.input_uri
    mapping      = var.mapping_uri
    output       = var.output_uri
  }

  tags = merge({
    Project   = "PIC-SURE HPDS ETL"
    Pipeline  = "migration"
    Temporary = "true"
  }, var.tags)
}
