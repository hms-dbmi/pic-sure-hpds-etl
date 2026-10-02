provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# PERMANENT. Generates the harmonized-data consent mapping file (ALS-12727) on a
# self-terminating EC2 instance: streams each consent group's Person.tsv from the
# latest DMC harmonization drop (read under the NHLBI exchange role passed as
# role_arn) and writes Person.Identity,study_id,consent_code CSV(s) to the
# configured output. Touches no database.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "generate-identity-consent-mapping"
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

  job_name  = "generate-identity-consent-mapping"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  # Touches no database: blank skips the credential fetch, so this job runs whether or
  # not the participant database is up.
  db_secret_id = ""

  job_params = merge(
    {
      base     = var.base_uri
      role_arn = var.input_role_arn
      output   = var.output_uri
    },
    var.dataset_prefix != "" ? { dataset_prefix = var.dataset_prefix } : {},
    var.per_study == "true" ? { per_study = "true" } : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
