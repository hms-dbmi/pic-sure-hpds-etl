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

variable "aws_account_id" {
  type        = string
  description = "Account the environment lives in; the provider refuses any other"
}

variable "vpc_id" {
  type        = string
  default     = ""
  description = "VPC to launch in; its lowest-id subnet is used when subnet_id is blank"
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet to launch the runner in. Must reach the participant DB and S3. Blank = looked up from vpc_id."
}

variable "iam_role_name" {
  type        = string
  default     = "bdc-etl-jenkins-role"
  description = "Instance profile role; the job does all S3 and Secrets Manager work as this role"
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the runner. When empty, the VPC default is used."
}

variable "root_volume_size" {
  type        = number
  default     = 30
  description = "Root EBS size in GiB. Only holds the container image and the JSON report."
}

variable "db_secret_id" {
  type        = string
  default     = ""
  description = "Participant DB secret, present only while participant-db-start's database is up"
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

variable "context_tar" {
  type        = string
  default     = "hpds-etl-context.tar.gz"
  description = <<-EOT
    Build-context tarball (JAR, Dockerfile, run-job.sh) under s3://<stack_s3_bucket>/etl-runner/container/;
    the instance builds the image from it. Jenkins passes a per-run name so two pipelines building
    different commits cannot overwrite each other's context between upload and instance boot.
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
