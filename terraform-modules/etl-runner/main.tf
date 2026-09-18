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
}

locals {
  # Blank name_suffix keeps the historical resource names; a per-run suffix lets two
  # concurrent runs of the same job coexist (needed by the SSTR study sweep).
  suffix = var.name_suffix == "" ? "" : "-${var.name_suffix}"

  reports_prefix = var.reports_s3_prefix != "" ? var.reports_s3_prefix : "etl-runner/reports/${var.module_name}/${var.run_id}"

  # Non-secret container environment, rendered as a docker --env-file. The RDS credentials
  # are appended to this file on the instance after being fetched from Secrets Manager, so
  # they never appear here, in Terraform state, or in the user-data blob.
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
      image_name      = trimsuffix(trimsuffix(var.image_tar, ".gz"), ".tar")
      image_tar       = var.image_tar
      rds_secret_id     = var.rds_secret_id
      rds_host          = var.rds_host
      rds_dbname        = var.rds_dbname
      reports_prefix    = local.reports_prefix
      container_env_b64 = local.container_env_b64
      container_assume_role_arn = var.container_assume_role_arn
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

resource "aws_iam_instance_profile" "etl_runner_profile" {
  name = "${var.module_name}-etl-instance-profile${local.suffix}"
  role = var.iam_role_name
  tags = local.merged_tags
}

# Optional, off by default: see the manage_secret_access variable for why.
resource "aws_iam_role_policy" "etl_runner_secret_access" {
  count = var.manage_secret_access ? 1 : 0

  name = "${var.module_name}-etl-secret-access${local.suffix}"
  role = var.iam_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.rds_secret_arn]
    }]
  })
}

module "etl_runner" {
  source  = "terraform-aws-modules/ec2-instance/aws"
  version = "6.0.1"

  name                                 = "${var.module_name}-etl-runner${local.suffix}"
  ami                                  = data.aws_ami.etl_base.id
  instance_type                        = var.instance_type
  subnet_id                            = var.subnet_id
  vpc_security_group_ids               = length(var.vpc_security_group_ids) > 0 ? var.vpc_security_group_ids : null
  iam_instance_profile                 = aws_iam_instance_profile.etl_runner_profile.name
  instance_initiated_shutdown_behavior = "terminate"

  user_data_replace_on_change = true

  metadata_options = {
    http_endpoint = "enabled"
    http_tokens   = "required"
    # 2 hops so the container (a second network hop) can still reach IMDS for the
    # instance-role credentials the AWS SDK inside the JAR uses to read s3:// inputs.
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

  user_data_base64 = base64encode(
    var.user_data_content != null
    ? var.user_data_content
    : templatefile("${path.module}/user_data.sh.tpl", local.template_vars)
  )

  tags = local.merged_tags
}
