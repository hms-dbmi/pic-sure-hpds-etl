provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT. Generates global_AllConcepts.csv from the populated participant database on a
# self-terminating EC2 instance. Reads every study marked ready in managed inputs,
# queries consents/participants/samples per study, and writes one CSV with concept
# rows to the configured output location.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "generate-global-all-concepts"
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

  job_name    = "generate-global-all-concepts"
  run_id      = var.run_id
  context_tar = var.context_tar
  java_opts   = var.java_opts
  log_level   = var.log_level

  db_secret_id = var.db_secret_id

  job_params = merge(
    { output = var.output_uri },
    var.managed_inputs_uri != "" ? { managed_inputs = var.managed_inputs_uri } : {},
    var.allow_empty != ""        ? { allow_empty = var.allow_empty }           : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
