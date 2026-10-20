terraform {
  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

data "aws_ami" "etl_base" {
  most_recent = true
  owners      = [var.ami_owner_id]
  filter {
    name   = "name"
    values = [var.ami_name_pattern]
  }

  # Cross-variable checks, here because this data source is always read (Terraform 1.3 has no
  # check blocks, and variable validations cannot reference other variables before 1.9).
  lifecycle {
    precondition {
      condition     = var.subnet_id != "" || var.vpc_id != ""
      error_message = "Set subnet_id (preferred) or vpc_id: with both blank there is nowhere to launch the runner."
    }
    precondition {
      condition     = !var.uses_participant_db || local.db_credentials_parameter != ""
      error_message = "uses_participant_db is true but there is no SSM parameter to read the credentials from: set environment (or participant_db_credentials_parameter)."
    }
  }
}

# Blank subnet_id: launch in the lowest-id subnet of vpc_id, so an environment can name only
# its VPC. Sorted so every run of every runner picks the same subnet -- but "lowest id" is an
# arbitrary choice (not the best-routed or a private subnet), so environments should pin
# subnet_id.
data "aws_subnets" "vpc" {
  count = var.subnet_id == "" && var.vpc_id != "" ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }

  lifecycle {
    postcondition {
      condition     = length(self.ids) > 0
      error_message = "vpc_id ${var.vpc_id} has no subnets; set subnet_id."
    }
  }
}

locals {
  # Only reached with one of the two set: the precondition on data.aws_ami.etl_base fails first.
  subnet_id = var.subnet_id != "" ? var.subnet_id : (var.vpc_id != "" ? sort(data.aws_subnets.vpc[0].ids)[0] : "")

  # Where a DB-backed runner reads the participant database's credentials at boot.
  db_credentials_parameter = (
    var.participant_db_credentials_parameter != "" ? var.participant_db_credentials_parameter
    : var.environment != "" ? "/hpds-etl/${var.environment}/participant-db/app-credentials"
    : ""
  )

  # Blank name_suffix keeps the historical resource names; a per-run suffix lets two
  # concurrent runs of the same job coexist (needed by the SSTR study sweep).
  suffix = var.name_suffix == "" ? "" : "-${var.name_suffix}"

  reports_prefix = var.reports_s3_prefix != "" ? var.reports_s3_prefix : "etl-runner/reports/${var.module_name}/${var.run_id}"

  # Non-secret container environment, rendered as a podman --env-file. The database credentials
  # are appended to this file on the instance, from the SSM parameter (see user_data.sh.tpl).
  #
  # base64-encoded so the value is opaque inside user_data: no heredoc delimiter to break
  # out of, and no newline injection into the env file from a crafted job_params value.
  container_env_b64 = base64encode(join("\n", concat(
    [
      "AWS_REGION=${var.aws_region}",
      "ETL_REPORTS_DIR=/reports",
      "ETL_JOB=${var.job_name}",
      "ETL_RUN_ID=${var.run_id}",
      "LOG_LEVEL=${var.log_level}",
      "JAVA_OPTS=${var.java_opts}",
    ],
    # Keys are normalised to env-var form here; run-job.sh maps them back to --flags.
    [for k, v in var.job_params : "ETL_PARAM_${replace(k, "-", "_")}=${v}"]
  )))

  template_vars = merge(
    {
      aws_region      = var.aws_region
      stack_s3_bucket = var.stack_s3_bucket
      module_name     = var.module_name
      job_name        = var.job_name
      run_id          = var.run_id
      # The image `docker save` names after the tarball (common.mk IMAGE_NAME).
      image_name               = trimsuffix(trimsuffix(var.image_tar, ".gz"), ".tar")
      image_tar                = var.image_tar
      uses_db                  = var.uses_participant_db
      db_credentials_parameter = local.db_credentials_parameter
      reports_prefix           = local.reports_prefix
      container_env_b64        = local.container_env_b64
      # Worker mode is off unless pool_prefix is set (etl-runners/runner-pool).
      pool_prefix       = var.pool_prefix
      pool_idle_timeout = var.pool_idle_timeout
    },
    var.user_data_template_vars
  )

  default_tags = {
    Environment = "etl"
    Module      = var.module_name
    Job         = var.job_name
    RunId       = var.run_id
    ManagedBy   = "terraform"
  }

  merged_tags = merge(local.default_tags, var.tags)
}

# Only when no pre-existing profile is named: this account does not allow creating instance
# profiles at runtime, so environments set iam_instance_profile_name.
resource "aws_iam_instance_profile" "etl_runner_profile" {
  count = var.iam_instance_profile_name == "" ? 1 : 0
  name  = "${var.module_name}-etl-instance-profile${local.suffix}"
  role  = var.iam_role_name
  tags  = local.merged_tags
}

module "etl_runner" {
  source  = "terraform-aws-modules/ec2-instance/aws"
  version = "6.0.1"

  name                   = "${var.module_name}-etl-runner${local.suffix}"
  ami                    = data.aws_ami.etl_base.id
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  vpc_security_group_ids = length(var.vpc_security_group_ids) > 0 ? var.vpc_security_group_ids : null
  # The upstream module creates a security group per instance by default; this account does
  # not allow creating them at runtime, so runners use only vpc_security_group_ids (whose
  # egress rules must allow S3, SSM, and package mirrors).
  create_security_group                = false
  iam_instance_profile                 = var.iam_instance_profile_name != "" ? var.iam_instance_profile_name : aws_iam_instance_profile.etl_runner_profile[0].name
  instance_initiated_shutdown_behavior = "terminate"

  user_data_replace_on_change = true

  metadata_options = {
    http_endpoint = "enabled"
    http_tokens   = "required"
    # 2 hops so the container (a second network hop) can still reach IMDS for the
    # instance-role credentials the AWS SDK inside the JAR uses for all S3 I/O.
    http_put_response_hop_limit = 2
  }

  # Avoid conflicting with root_block_device.tags in the upstream module.
  enable_volume_tags = false

  root_block_device = var.root_volume_size != null ? {
    size                  = var.root_volume_size
    type                  = var.root_volume_type
    iops                  = var.root_volume_type == "gp3" ? var.root_volume_iops : null
    throughput            = var.root_volume_type == "gp3" ? var.root_volume_throughput : null
    encrypted             = true
    delete_on_termination = true
  } : null

  # Gzipped: EC2 caps user data at 16 KB, and the worker-mode script alone is close to that.
  # cloud-init detects and decompresses gzip itself.
  user_data_base64 = base64gzip(
    var.user_data_content != null
    ? var.user_data_content
    : templatefile("${path.module}/user_data.sh.tpl", local.template_vars)
  )

  tags = local.merged_tags
}
