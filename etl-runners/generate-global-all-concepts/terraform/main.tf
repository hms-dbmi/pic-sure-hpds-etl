provider "aws" {
  region = var.aws_region
}

# PERMANENT. Generates global_AllConcepts.csv from the populated RDS database on a
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
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = "jenkins-s3-role"
  root_volume_size = var.root_volume_size

  job_name  = "generate-global-all-concepts"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  rds_secret_id        = var.rds_secret_id
  rds_secret_arn       = var.rds_secret_arn
  manage_secret_access = var.manage_secret_access
  rds_host             = var.rds_host
  rds_dbname           = var.rds_dbname

  job_params = merge(
    { output = var.output_uri },
    var.managed_inputs_uri != "" ? { managed_inputs = var.managed_inputs_uri } : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
