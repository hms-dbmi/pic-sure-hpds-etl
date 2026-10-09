provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT. Loads one dbGaP study's SSTR subject/sample mapping TSV into the participants,
# consents, and samples participant-DB tables on a self-terminating EC2 instance. One instance per
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
  vpc_id                 = var.vpc_id
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = var.iam_role_name
  root_volume_size = var.root_volume_size

  job_name    = "sstr-populate-rds-participants"
  run_id      = var.run_id
  context_tar = var.context_tar
  java_opts   = var.java_opts
  log_level   = var.log_level

  db_secret_id = var.db_secret_id

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
