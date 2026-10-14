# --- Infrastructure (from environments/<ENV>.tfvars and runner-pool.tfvars) ---------------

variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "aws_account_id" {
  type        = string
  description = "Account the environment lives in; the provider refuses any other"
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
  description = "VPC to launch in; its lowest-id subnet is used when subnet_id is blank"
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet for the workers. Must reach the participant DB and S3."
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

variable "root_volume_size" {
  type        = number
  default     = 50
  description = "Root EBS size in GiB per worker"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}

# --- Per pool (supplied by Jenkins as TF_VAR_*) ---------------------------------------------

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
  description = "Job the workers run (instance names and tags only; tasks name their own job)"
}

variable "pool_size" {
  type        = number
  description = "Number of worker instances"
  validation {
    condition     = var.pool_size >= 1 && var.pool_size <= 50
    error_message = "pool_size must be between 1 and 50."
  }
}

variable "instance_type" {
  type        = string
  default     = "r6i.large"
  description = "Worker instance type"
}

variable "image_tar" {
  type        = string
  description = "Runner image tarball under s3://<stack_s3_bucket>/etl-runner/container/, loaded once per worker"
}

variable "uses_participant_db" {
  type        = bool
  default     = true
  description = "Pass the participant DB credentials to the workers"
}

variable "db_credentials_json" {
  type        = string
  default     = ""
  sensitive   = true
  description = "Participant DB credentials, from the Jenkins server's saved copy (common.mk sets TF_VAR_db_credentials_json)"
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
