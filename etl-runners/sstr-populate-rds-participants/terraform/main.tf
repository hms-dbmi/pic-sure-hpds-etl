provider "aws" {
  region = var.aws_region
}

# PERMANENT. Loads one dbGaP study's SSTR subject/sample mapping TSV into the participants,
# consents, and samples RDS tables on a self-terminating EC2 instance. One instance per
# study: the job is scoped to a single --study-id and purges that study's consents before
# reloading them, so studies never interfere with one another.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "sstr-populate-rds-participants"
  name_suffix      = var.name_suffix
  stack_s3_bucket  = var.stack_s3_bucket
  ami_owner_id     = var.ami_owner_id
  ami_name_pattern = var.ami_name_pattern
  instance_type    = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = "jenkins-s3-role"
  root_volume_size = var.root_volume_size

  job_name  = "sstr-populate-rds-participants"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  rds_secret_id        = var.rds_secret_id
  rds_secret_arn       = var.rds_secret_arn
  manage_secret_access = var.manage_secret_access
  rds_host             = var.rds_host
  rds_dbname           = var.rds_dbname

  container_assume_role_arn = var.container_assume_role_arn

  # Keys use underscores; the runner converts them to --input, --study-id, --batch-size.
  # Names must match SstrPopulateRdsParticipantsJob.expectations().
  job_params = {
    input      = var.input_uri
    study_id   = var.study_id
    batch_size = var.batch_size
  }

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
    StudyId  = var.study_id
  }, var.tags)
}
