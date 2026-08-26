variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "module_name" {
  type        = string
  description = "Name of the ETL runner (e.g. participants-migration). Used in resource names and tags."
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = <<-EOT
    Appended to every uniquely-named resource (instance, instance profile, IAM policy).
    Pass a per-run value (e.g. the Jenkins BUILD_NUMBER) so two concurrent runs of the
    same job do not collide on an AWS resource name. Blank means single-run-at-a-time.
  EOT
}

variable "stack_s3_bucket" {
  type        = string
  description = "S3 bucket holding the Docker image tarball, run logs, and job reports."
}

variable "ami_owner_id" {
  type        = string
  default     = "amazon"
  description = "AMI owner account ID. Use 'amazon' for official Amazon Linux AMIs."
}

variable "ami_name_pattern" {
  type        = string
  default     = "al2023-ami-2023.*-x86_64"
  description = "Glob pattern selecting the most recent matching AMI. Defaults to Amazon Linux 2023 x86_64."
}

variable "instance_type" {
  type        = string
  default     = "m5.large"
  description = "EC2 instance type."
}

variable "subnet_id" {
  type        = string
  description = "Subnet to launch the instance in."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the instance. When empty, the VPC default security group is used."
}

variable "iam_role_name" {
  type        = string
  default     = "jenkins-s3-role"
  description = <<-EOT
    Pre-existing IAM role attached as the instance profile. This module does not create
    the role. It must allow: s3 read on the container tarball, s3 write on the log and
    report prefixes, secretsmanager:GetSecretValue on var.rds_secret_arn, ssm core, and
    read on any bucket holding job input. See README for the policy.
  EOT
}

# ---------------------------------------------------------------------------
# hpds-etl job contract
#
# The container runs exactly one job from the hpds-etl fat JAR and exits with that
# job's ExitCode (0 success, 2 validation, 3 data, 4 infrastructure, 5 config).
# ---------------------------------------------------------------------------

variable "job_name" {
  type        = string
  description = "Value passed to the JAR's --job (e.g. participants-migration)."
}

variable "run_id" {
  type        = string
  description = "Correlation id passed to --run-id. Appears in the report filename and log MDC."
}

variable "job_params" {
  type        = map(string)
  default     = {}
  description = <<-EOT
    Job parameters, exposed to the container as ETL_PARAM_<key> environment variables and
    turned back into --key=value by run-job.sh. Use underscores in keys; run-job.sh maps
    them to hyphens (study_id -> --study-id). Passing them as env vars rather than as an
    argv string keeps generated user_data free of shell-quoting hazards.
  EOT

  validation {
    condition     = alltrue([for k, v in var.job_params : !can(regex("\n", v))])
    error_message = "job_params values must not contain newlines -- they are written as lines in a docker --env-file."
  }
}

variable "image_name" {
  type        = string
  default     = "hpds-etl-runner"
  description = "Docker image name (repository) loaded from the tarball."
}

variable "image_tar" {
  type        = string
  default     = "hpds-etl-runner.tar.gz"
  description = "Tarball filename under s3://<stack_s3_bucket>/etl-runner/container/."
}

variable "java_opts" {
  type        = string
  default     = "-XX:MaxRAMPercentage=75"
  description = "JAVA_OPTS for the JVM in the container. Sized against the instance, not the host default."
}

variable "log_level" {
  type        = string
  default     = "INFO"
  description = "LOG_LEVEL for edu.harvard.hms.dbmi.avillach.hpds loggers."
}

variable "rds_secret_id" {
  type        = string
  description = <<-EOT
    Secrets Manager secret id/name holding the RDS credentials. The runner fetches it with
    its instance profile at run time; the values never enter Terraform state, the Jenkins
    console, or the EC2 user-data blob. Expected JSON keys: url (a full JDBC URL) or
    host/port/dbname, plus username and password.
  EOT
}

variable "rds_secret_arn" {
  type        = string
  default     = ""
  description = "ARN of the RDS secret. Only needed when manage_secret_access is true."
}

variable "manage_secret_access" {
  type        = bool
  default     = false
  description = <<-EOT
    When true, attaches an inline secretsmanager:GetSecretValue policy for rds_secret_arn
    to var.iam_role_name. Defaults to false because that role is shared with other
    pipelines and is normally managed outside this repo -- prefer granting the permission
    once, centrally, over having each ETL run mutate a shared role.
  EOT
}

variable "reports_s3_prefix" {
  type        = string
  default     = ""
  description = <<-EOT
    S3 prefix (no bucket, no leading slash) the runner syncs the reports directory to.
    Defaults to etl-runner/reports/<module_name>/<run_id> when blank.
  EOT
}

variable "user_data_template_vars" {
  type        = map(string)
  default     = {}
  description = "Extra variables merged into the built-in user_data.sh.tpl template."
}

variable "user_data_content" {
  type        = string
  default     = null
  description = "Fully rendered user_data. When set, overrides the built-in template entirely."
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional tags applied to all resources."
}

variable "root_volume_size" {
  type        = number
  default     = null
  description = "Root EBS volume size in GiB (null uses the AMI default)."
}

variable "root_volume_type" {
  type        = string
  default     = "gp3"
  description = "Root EBS volume type."
}

variable "root_volume_iops" {
  type        = number
  default     = 3000
  description = "Provisioned IOPS (gp3 only)."
}

variable "root_volume_throughput" {
  type        = number
  default     = 125
  description = "Provisioned throughput in MB/s (gp3 only)."
}
