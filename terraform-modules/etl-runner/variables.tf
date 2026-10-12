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
  default     = "752463128620"
  description = "AMI owner account ID. Defaults to the SRCE golden-image account."
}

variable "ami_name_pattern" {
  type        = string
  default     = "srce-rhel9-golden*"
  description = "Glob pattern selecting the most recent matching AMI. Defaults to the SRCE RHEL9 golden image, whose container runtime (podman) user_data.sh.tpl assumes."
}

variable "instance_type" {
  type        = string
  default     = "m5.large"
  description = "EC2 instance type."
}

variable "vpc_id" {
  type        = string
  default     = ""
  description = "VPC to launch in. Only consulted when subnet_id is blank, to pick its lowest-id subnet."
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet to launch the instance in. Blank picks the lowest-id subnet of vpc_id (one of the two must be set)."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the instance. When empty, the VPC default security group is used."
}

variable "iam_role_name" {
  type        = string
  default     = "bdc-etl-jenkins-role"
  description = <<-EOT
    Pre-existing IAM role attached as the instance profile. This module does not create
    the role. It is the only principal the job runs as: it must allow s3 read on the
    container tarball, s3 write on the log and report prefixes, read/write on the data
    bucket, and ssm core. See README for the policy.
  EOT
}

variable "iam_instance_profile_name" {
  type        = string
  default     = ""
  description = <<-EOT
    Pre-existing instance profile to attach (it must wrap iam_role_name). Blank creates a
    per-run profile, <module_name>-etl-instance-profile[-<name_suffix>], around iam_role_name.
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
    error_message = "job_params values must not contain newlines -- they are written as lines in a podman --env-file."
  }
}

variable "image_name" {
  type        = string
  default     = "hpds-etl-runner"
  description = "Image name (repository) podman loads from the tarball."
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

variable "uses_participant_db" {
  type        = bool
  default     = false
  description = <<-EOT
    Whether the job needs the participant database. False skips the credential step, so jobs
    that never touch the database run whether or not it is up. True requires
    db_credentials_json: a runner without it fails in its credentials phase (exit 5).
  EOT
}

variable "db_credentials_json" {
  type        = string
  default     = ""
  sensitive   = true
  description = <<-EOT
    The participant database's credentials, as saved on the Jenkins server by
    participant-db-start (etl-runners/common/db-credentials-path.sh); common.mk passes them as
    TF_VAR_db_credentials_json. JSON keys: url (a full JDBC URL) or host/port/dbname, plus
    username and password. Written into the runner's 600-mode container env file through user
    data, so the password is in this runner's EC2 user data and Terraform state for its life.
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
