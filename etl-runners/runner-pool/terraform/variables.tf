# --- Infrastructure (from environments/<ENV>.tfvars and runner-pool.tfvars) ---------------

variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "aws_account_id" {
  type        = string
  description = "Account the environment lives in; the provider refuses any other"
}

variable "environment" {
  type        = string
  description = "ETL environment (common.mk sets TF_VAR_environment from ENV). Names the participant DB credentials' SSM parameter."
}

variable "stack_s3_bucket" {
  type        = string
  description = "Bucket holding the container tarball, mailboxes, run logs, and job reports"
}

variable "ami_owner_id" {
  type        = string
  description = "AMI owner account id"
}

variable "ami_name_pattern" {
  type        = string
  description = "Glob selecting the most recent matching AMI"
}

variable "vpc_id" {
  type        = string
  default     = ""
  description = "VPC to launch in; its lowest-id subnet (an arbitrary pick) is used when subnet_id is blank"
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet for the workers (preferred over vpc_id). Must reach S3, and the participant DB when uses_participant_db."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs for the workers"
}

variable "iam_role_name" {
  type        = string
  default     = "bdc-etl-jenkins-role"
  description = "Instance profile role; the jobs do all S3 work as this role"
}

variable "iam_instance_profile_name" {
  type        = string
  default     = ""
  description = "Pre-existing instance profile to attach (environments/<ENV>.tfvars)"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}

# --- Per pool (supplied by the caller as TF_VAR_*) ------------------------------------------

variable "run_id" {
  type        = string
  description = "Pool id. Mailboxes live under etl-runner/pools/<run_id>/."
  validation {
    condition     = can(regex("^[A-Za-z0-9._-]+$", var.run_id))
    error_message = "run_id may contain only letters, digits, '.', '_' and '-'."
  }
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = "Per-pool suffix keeping AWS resource names unique (Jenkins BUILD_NUMBER)"
}

variable "pool_job" {
  type        = string
  description = <<-EOT
    Names the workers: their module_name and job_name (instance names, tags, and the worker log
    etl-runner/logs/<pool_job>-<run_id>-<instance-id>.log). Tasks name their own job, so one pool
    can run any job the image holds.
  EOT
  validation {
    condition     = can(regex("^[A-Za-z0-9._-]+$", var.pool_job))
    error_message = "pool_job may contain only letters, digits, '.', '_' and '-'."
  }
}

variable "pool_size" {
  type        = number
  description = "Number of worker instances"
  validation {
    condition     = var.pool_size >= 1 && var.pool_size <= 50 && floor(var.pool_size) == var.pool_size
    error_message = "pool_size must be a whole number from 1 to 50."
  }
}

variable "instance_type" {
  type        = string
  default     = "m5.large"
  description = "Worker instance type, sized for the heaviest job the pool will run"
}

variable "root_volume_size" {
  type        = number
  default     = 50
  description = "Root EBS size in GiB per worker: the container image plus the largest task's working files"
}

variable "image_tar" {
  type        = string
  description = "Runner image tarball under s3://<stack_s3_bucket>/etl-runner/container/, loaded once per worker"
}

variable "uses_participant_db" {
  type        = bool
  default     = false
  description = "Give the workers the participant DB credentials (read from SSM once, at boot)"
}

variable "idle_timeout_seconds" {
  type        = number
  default     = 1800
  description = "A worker with no task for this long terminates itself: the backstop if the pipeline dies before destroying the pool."
}

variable "java_opts" {
  type        = string
  default     = "-XX:MaxRAMPercentage=75"
  description = "JAVA_OPTS for the container JVM"
}

variable "log_level" {
  type        = string
  default     = "INFO"
  description = "LOG_LEVEL for the hpds loggers"
}
