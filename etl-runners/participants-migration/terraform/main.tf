provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# TEMPORARY. Runs the one-off participants-migration job (JobType.MIGRATION) on a
# self-terminating EC2 instance. Delete this directory together with the job once the
# migration has run in every environment.
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "participants-migration"
  name_suffix      = var.name_suffix
  stack_s3_bucket  = var.stack_s3_bucket
  ami_owner_id     = var.ami_owner_id
  ami_name_pattern = var.ami_name_pattern
  instance_type    = var.instance_type
  vpc_id                 = var.vpc_id
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = var.iam_role_name
  iam_instance_profile_name = var.iam_instance_profile_name
  root_volume_size = var.root_volume_size

  job_name  = "participants-migration"
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  uses_participant_db = true
  db_credentials_json = var.db_credentials_json

  # Keys use underscores; the runner converts them to --managed-inputs, --data-folder,
  # --batch-size. Names must match ParticipantsMigrationJob.expectations().
  job_params = merge(
    {
      managed_inputs = var.managed_inputs_uri
      data_folder    = var.data_folder_uri
      batch_size     = var.batch_size
      parallelism    = var.parallelism
    },
    var.study_filter != "" ? { study_filter = var.study_filter } : {},
    # The earlier run's report prefix, as terraform-modules/etl-runner lays it out.
    var.resume_from_run_id != "" ? {
      resume_from = "s3://${var.stack_s3_bucket}/etl-runner/reports/participants-migration/${var.resume_from_run_id}/"
    } : {}
  )

  tags = merge({
    Project   = "PIC-SURE HPDS ETL"
    Pipeline  = "migration"
    Temporary = "true"
  }, var.tags)
}
