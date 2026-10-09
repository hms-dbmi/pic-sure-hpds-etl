provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT. Generates per-consent allConcepts CSV files for a single study on a
# self-terminating EC2 instance. Reads decoded data CSVs and a mapping file,
# resolves participant/consent associations from the participant DB, runs data type analysis,
# and writes one {study_id}.c{consent_code}_allConcepts.csv per consent group.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "all-concepts-data-generator"
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

  job_name    = "all-concepts-data-generator"
  run_id      = var.run_id
  context_tar = var.context_tar
  java_opts   = var.java_opts
  log_level   = var.log_level

  db_secret_id = var.db_secret_id

  # Keys use underscores like every other runner; run-job.sh maps them to --study-id etc.
  job_params = {
    study_id      = var.study_id
    data_dir      = var.data_dir
    mapping       = var.mapping_uri
    output        = var.output_uri
    skip_analysis = var.skip_analysis
  }

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
