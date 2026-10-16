provider "aws" {
  region = var.aws_region
}

# PERMANENT. Splits the one merged allConcepts CSV into per-consent partition files on a
# self-terminating EC2 instance. Touches no database: the rds_* values below are required by the
# shared etl-runner module and come from environments/<ENV>.tfvars, as in the other DB-less
# runner (generate-identity-consent-mapping).
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region             = var.aws_region
  module_name            = "split-allconcepts-by-consent"
  name_suffix            = var.name_suffix
  stack_s3_bucket        = var.stack_s3_bucket
  ami_owner_id           = var.ami_owner_id
  ami_name_pattern       = var.ami_name_pattern
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = "jenkins-s3-role"
  root_volume_size       = var.root_volume_size

  job_name  = "split-allconcepts-by-consent"
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

  # Keys use underscores; the runner converts them to --input, --output, --fence-mapping, etc.
  # Names must match SplitMergedAllConceptsJob.expectations(). Optional values are omitted when
  # blank so the job's own defaults apply (and a backslash-heavy consents_path never has to be
  # carried through user-data unless someone really sets it).
  job_params = merge(
    {
      input   = var.input_uri
      output  = var.output_uri
      depth   = tostring(var.depth)
      globals = var.globals
      force   = tostring(var.force)
    },
    var.fence_mapping != "" ? { fence_mapping = var.fence_mapping } : {},
    var.consents_path != "" ? { consents_path = var.consents_path } : {}
  )

  tags = merge({
    Project  = "PIC-SURE HPDS ETL"
    Pipeline = "permanent"
  }, var.tags)
}
