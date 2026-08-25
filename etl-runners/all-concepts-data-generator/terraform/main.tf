provider "aws" {
  region = var.aws_region
}

# PERMANENT. Generates per-consent allConcepts CSV files for a single study on a
# self-terminating EC2 instance. Reads decoded data CSVs and a mapping file,
# resolves participant/consent associations from RDS, runs data type analysis,
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
  subnet_id        = var.subnet_id
  iam_role_name    = "jenkins-s3-role"
  root_volume_size = var.root_volume_size

  job_name  = "all-concepts-data-generator"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  rds_secret_id        = var.rds_secret_id
  rds_secret_arn       = var.rds_secret_arn
  manage_secret_access = var.manage_secret_access

  job_params = {
    study-id      = var.study_id
    data-dir      = var.data_dir
    mapping       = var.mapping_uri
    output        = var.output_uri
    skip-analysis = var.skip_analysis
  }

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
