provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# A pool of etl-runner instances in worker mode, for any job: each boots once, then runs the tasks
# ../../common/pool-dispatch.sh writes to its mailbox, <pool_prefix>/<instance-id>/task.json (see
# the module's user_data.sh.tpl and README). Available, but not yet used by the permanent pipeline.
locals {
  pool_prefix = "etl-runner/pools/${var.run_id}"
  workers     = toset([for i in range(var.pool_size) : format("%02d", i + 1)])
}

module "worker" {
  source   = "../../../terraform-modules/etl-runner"
  for_each = local.workers

  aws_region                = var.aws_region
  module_name               = var.pool_job
  name_suffix               = "${var.name_suffix}-w${each.key}"
  stack_s3_bucket           = var.stack_s3_bucket
  ami_owner_id              = var.ami_owner_id
  ami_name_pattern          = var.ami_name_pattern
  instance_type             = var.instance_type
  vpc_id                    = var.vpc_id
  subnet_id                 = var.subnet_id
  vpc_security_group_ids    = var.vpc_security_group_ids
  iam_role_name             = var.iam_role_name
  iam_instance_profile_name = var.iam_instance_profile_name
  root_volume_size          = var.root_volume_size

  # job_name and run_id name the pool; each task brings its own job, run id and parameters.
  job_name  = var.pool_job
  run_id    = var.run_id
  image_tar = var.image_tar
  java_opts = var.java_opts
  log_level = var.log_level

  environment         = var.environment
  uses_participant_db = var.uses_participant_db

  # Nothing is published here in worker mode -- each task publishes under its own reportsPrefix --
  # but it keeps the module's per-instance outputs pointing somewhere distinct per worker.
  reports_s3_prefix = "${local.pool_prefix}/w${each.key}"

  pool_prefix       = local.pool_prefix
  pool_idle_timeout = var.idle_timeout_seconds

  tags = merge({
    Pipeline = "runner-pool"
    PoolJob  = var.pool_job
    Worker   = each.key
  }, var.tags)
}
