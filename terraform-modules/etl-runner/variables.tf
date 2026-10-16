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
  description = <<-EOT
    VPC to launch in. Only consulted when subnet_id is blank, to pick its lowest-id subnet. That
    pick is arbitrary (whichever subnet id sorts first, not the best-routed one), so prefer
    pinning subnet_id.
  EOT
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet to launch the instance in. Blank picks the lowest-id subnet of vpc_id (one of the two must be set; pinning this is preferred)."
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
    # Hyphens are allowed: they become underscores in the env var name.
    condition     = alltrue([for k in keys(var.job_params) : can(regex("^[A-Za-z_][A-Za-z0-9_-]*$", k))])
    error_message = "job_params keys must start with a letter or '_' and contain only letters, digits, '_' and '-': they become ETL_PARAM_<key> environment variable names."
  }

  validation {
    condition     = alltrue([for v in values(var.job_params) : !can(regex("[\r\n]", v))])
    error_message = "job_params values must not contain carriage returns or newlines: they are written as lines in a podman --env-file."
  }
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
    that never touch the database run whether or not it is up. True makes the instance read the
    credentials from the SSM parameter participant_db_credentials_parameter at boot, as the
    instance role; a runner that cannot read a valid one fails in its credentials phase (exit 5).
  EOT
}

variable "environment" {
  type        = string
  default     = ""
  description = <<-EOT
    The ETL environment: the <ENV> of etl-runners/environments/<ENV>.tfvars, e.g. development.
    Names the default participant_db_credentials_parameter, and is otherwise unused.
  EOT

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]*$", var.environment))
    error_message = "environment may contain only letters, digits, '.', '_' and '-'."
  }
}

variable "participant_db_credentials_parameter" {
  type        = string
  default     = ""
  description = <<-EOT
    SSM SecureString parameter holding the participant database's app credentials as JSON (see
    the README), which participant-db-start rewrites each time it rotates the password. Read at
    boot by the instance role when uses_participant_db is true. Blank derives
    /hpds-etl/<environment>/participant-db/app-credentials. Only the name reaches user data and
    Terraform state, never the credentials.
  EOT

  validation {
    condition     = var.participant_db_credentials_parameter == "" || can(regex("^/[A-Za-z0-9_./-]+$", var.participant_db_credentials_parameter))
    error_message = "participant_db_credentials_parameter must be blank or a fully-qualified SSM parameter name (/a/b/c: letters, digits, '_', '.', '-', '/')."
  }
}

variable "reports_s3_prefix" {
  type        = string
  default     = ""
  description = <<-EOT
    S3 prefix (no bucket, no leading slash) the runner syncs the reports directory to.
    Defaults to etl-runner/reports/<module_name>/<run_id> when blank.
  EOT
}

# ---------------------------------------------------------------------------
# Worker mode (runner pool). Off by default: the instance runs the one job above and terminates.
# Usable by any job, but not yet used by the permanent pipeline (README, "Worker mode").
# ---------------------------------------------------------------------------

variable "pool_prefix" {
  type        = string
  default     = ""
  description = <<-EOT
    Blank (the default): single mode -- run job_name once and terminate. Set: worker mode -- boot
    once, then run tasks from this instance's S3 mailbox,
    s3://<stack_s3_bucket>/<pool_prefix>/<instance-id>/task.json, one at a time (written by
    etl-runners/common/pool-dispatch.sh), until a `stop` object appears there or
    pool_idle_timeout seconds pass with no task. An S3 key prefix: no bucket, no leading or
    trailing slash.
  EOT

  validation {
    condition     = var.pool_prefix == "" || (can(regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$", var.pool_prefix)) && !can(regex("\\.\\.", var.pool_prefix)))
    error_message = "pool_prefix must be blank or an S3 key prefix of letters, digits, '.', '_', '-' and '/', with no leading or trailing slash and no '..'."
  }
}

variable "pool_idle_timeout" {
  type        = number
  default     = 1800
  description = "Worker mode only: seconds with no task after which a worker terminates itself -- the backstop when the dispatcher dies without stopping its pool."

  validation {
    condition     = var.pool_idle_timeout >= 60 && var.pool_idle_timeout <= 86400 && floor(var.pool_idle_timeout) == var.pool_idle_timeout
    error_message = "pool_idle_timeout must be a whole number of seconds from 60 to 86400."
  }
}

variable "user_data_template_vars" {
  type        = map(string)
  default     = {}
  description = "Extra variables merged into the built-in user_data.sh.tpl template, overriding the module's own."
}

variable "user_data_content" {
  type        = string
  default     = null
  description = "Fully rendered user_data. When set, overrides the built-in template entirely. Gzipped for EC2 like the template."
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
