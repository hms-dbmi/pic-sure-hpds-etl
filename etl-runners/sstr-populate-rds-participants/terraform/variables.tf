# --- Infrastructure (from sstr-populate-rds-participants.tfvars) -----------

variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "stack_s3_bucket" {
  type        = string
  description = "Bucket holding the container tarball, run logs, and job reports"
}

variable "ami_owner_id" {
  type        = string
  description = "AMI owner account id (or 'aws-marketplace')"
}

variable "ami_name_pattern" {
  type        = string
  description = "Glob selecting the most recent matching AMI"
}

variable "instance_type" {
  type        = string
  default     = "m5.large"
  description = <<-EOT
    Instance type. The job holds one Telemetry record per input row in memory, so size it
    against the largest SSTR file rather than the average one. m5.large comfortably covers
    a few million rows; bump it for the very large studies.
  EOT
}

variable "subnet_id" {
  type        = string
  description = "Subnet to launch the runner in. Must have a route to RDS and to S3."
}

variable "root_volume_size" {
  type        = number
  default     = 30
  description = "Root EBS size in GiB. Only holds the container image and the JSON report."
}

variable "rds_secret_id" {
  type        = string
  description = "Secrets Manager id holding the RDS credentials"
}

variable "rds_secret_arn" {
  type        = string
  default     = ""
  description = "Secret ARN; only needed when manage_secret_access is true"
}

variable "manage_secret_access" {
  type        = bool
  default     = false
  description = "Let this run attach a GetSecretValue policy to the instance role"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}

# --- Per-run (supplied by Jenkins as TF_VAR_*) -----------------------------

variable "run_id" {
  type        = string
  description = "Correlation id passed to the job as --run-id. Includes the study id when sweeping."
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = <<-EOT
    Per-run suffix keeping AWS resource names unique. The permanent pipeline may load
    several studies concurrently, so this must differ per study, not just per build.
  EOT
}

variable "study_id" {
  type        = string
  description = "--study-id: the dbGaP study these rows belong to, format phs###### (6 digits)"

  validation {
    condition     = can(regex("^phs[0-9]{6}$", var.study_id))
    error_message = "study_id must match phs###### (exactly 6 digits) -- the same rule SstrPopulateRdsParticipantsJob enforces."
  }
}

variable "input_uri" {
  type        = string
  description = "--input: the dbGaP SSTR subject/sample mapping TSV. Local path or s3:// URI."

  validation {
    condition     = can(regex("^(s3://[a-zA-Z0-9._+~@=/-]+|/[a-zA-Z0-9._+~@=/-]+)$", var.input_uri))
    error_message = "input_uri must be an s3:// URI or an absolute local path containing only safe path characters."
  }
}

variable "batch_size" {
  type        = string
  default     = "1000"
  description = "--batch-size: rows per batch insert"
}

variable "image_tar" {
  type        = string
  default     = "hpds-etl-runner.tar.gz"
  description = <<-EOT
    Container tarball under s3://<stack_s3_bucket>/etl-runner/container/. Jenkins passes a
    per-run name so two pipelines building different commits cannot overwrite each other's
    image between upload and instance boot.
  EOT
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
