provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# SHARED: the CodeBuild project the orchestrators run the Testcontainers *IT suites in, so the
# Jenkins agent needs no container runtime. Long-lived (one per environment), unlike the
# per-run runners: applied once, then every pipeline run starts builds in it through
# run-codebuild.sh.
#
# Each build gets its source from a zip run-codebuild.sh uploads, and returns its JUnit
# reports to the same per-run prefix:
#   s3://<stack_s3_bucket>/etl-runner/codebuild/<run-tag>/source.zip
#   s3://<stack_s3_bucket>/etl-runner/codebuild/<run-tag>/reports/target/{surefire,failsafe}-reports/

locals {
  prefix     = "etl-runner/codebuild"
  bucket_arn = "arn:aws:s3:::${var.stack_s3_bucket}"

  tags = merge({
    ManagedBy = "terraform"
    Module    = "integration-tests"
  }, var.tags)
}

resource "aws_cloudwatch_log_group" "build" {
  name              = "/aws/codebuild/${var.project_name}"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

# --- Service role: what a build may do -----------------------------------------
#
# Only what the build itself touches: its log group, and the codebuild/ prefix of the stack
# bucket. No VPC config, so no EC2/ENI permissions: the build needs Maven Central and the
# container registries, not anything inside the VPC.

resource "aws_iam_role" "codebuild" {
  name = "${var.project_name}-codebuild"
  tags = local.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "codebuild.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = var.aws_account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "codebuild" {
  name = "${var.project_name}-codebuild"
  role = aws_iam_role.codebuild.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "Logs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.build.arn}:*"]
      },
      {
        Sid      = "SourceAndReports"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"]
        Resource = ["${local.bucket_arn}/${local.prefix}/*"]
      },
      {
        Sid      = "ArtifactBucket"
        Effect   = "Allow"
        Action   = ["s3:GetBucketLocation", "s3:GetBucketAcl"]
        Resource = [local.bucket_arn]
      },
    ]
  })
}

# --- Project -----------------------------------------------------------------

resource "aws_codebuild_project" "integration_tests" {
  name           = var.project_name
  description    = "hpds-etl unit + Testcontainers integration suites (./mvnw verify) for one commit"
  service_role   = aws_iam_role.codebuild.arn
  build_timeout  = var.build_timeout_minutes
  queued_timeout = 60
  tags           = local.tags

  # Placeholder location: every build overrides it with its own uploaded zip. The buildspec
  # travels inside the zip, so a buildspec change ships with the commit, not with an apply.
  source {
    type      = "S3"
    location  = "${var.stack_s3_bucket}/${local.prefix}/source.zip"
    buildspec = "etl-runners/integration-tests/buildspec.yml"
  }

  # Also overridden per build (per-run path); this is the fallback for a manual start.
  artifacts {
    type           = "S3"
    location       = var.stack_s3_bucket
    path           = local.prefix
    name           = "reports"
    packaging      = "NONE"
    namespace_type = "BUILD_ID"
  }

  environment {
    type                        = "LINUX_CONTAINER"
    image                       = var.build_image
    compute_type                = var.compute_type
    image_pull_credentials_type = "CODEBUILD"
    # Testcontainers starts Postgres and LocalStack through the build's Docker daemon, which
    # CodeBuild only runs in privileged mode.
    privileged_mode = true
  }

  # Best-effort reuse of the Maven repository and pulled layers between back-to-back builds.
  cache {
    type  = "LOCAL"
    modes = ["LOCAL_CUSTOM_CACHE", "LOCAL_DOCKER_LAYER_CACHE"]
  }

  logs_config {
    cloudwatch_logs {
      status     = "ENABLED"
      group_name = aws_cloudwatch_log_group.build.name
    }
    s3_logs {
      status = "DISABLED"
    }
  }

  depends_on = [aws_iam_role_policy.codebuild]
}

# --- Jenkins: permission to drive this project ----------------------------------
#
# Attached to the role Jenkins already runs as (the same pattern participant-db uses for its
# secret). The agent already reads and writes the stack bucket, so the source upload and
# report download need nothing new.

resource "aws_iam_role_policy" "jenkins" {
  name = "${var.project_name}-jenkins"
  role = var.iam_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RunBuilds"
        Effect   = "Allow"
        Action   = ["codebuild:StartBuild", "codebuild:BatchGetBuilds", "codebuild:StopBuild"]
        Resource = [aws_codebuild_project.integration_tests.arn]
      },
      {
        Sid      = "ReadBuildLogs"
        Effect   = "Allow"
        Action   = ["logs:GetLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.build.arn}:*"]
      },
    ]
  })
}
