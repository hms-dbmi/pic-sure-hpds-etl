# --- Infrastructure (from participants-migration.tfvars) -------------------

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
  default     = "r6i.large"
  description = <<-EOT
    Instance type. The migration reads a whole study's patient-mapping and consents files
    into memory at once, so it is memory-bound rather than CPU-bound.
  EOT
}

variable "subnet_id" {
  type        = string
  description = "Subnet to launch the runner in. Must have a route to RDS and to S3."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the runner. When empty, the VPC default is used."
}

variable "root_volume_size" {
  type        = number
  default     = 50
  description = "Root EBS size in GiB. Holds the container image plus the per-study mapping CSVs."
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

variable "rds_host" {
  type        = string
  default     = ""
  description = "RDS endpoint hostname, used when the secret contains only username/password"
}

variable "rds_dbname" {
  type        = string
  default     = ""
  description = "RDS database name, used when the secret contains only username/password"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}

# --- Per-run (supplied by Jenkins as TF_VAR_*) -----------------------------

variable "run_id" {
  type        = string
  description = "Correlation id passed to the job as --run-id (Jenkins BUILD_TAG)"
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = "Per-run suffix keeping AWS resource names unique (Jenkins BUILD_NUMBER)"
}

variable "managed_inputs_uri" {
  type        = string
  description = <<-EOT
    --managed-inputs: CSV of studies with columns 'Study Abbreviated Name',
    'Study Identifier', and 'Data is ready to process'. Local path or s3:// URI.
  EOT
}

variable "data_folder_uri" {
  type        = string
  description = <<-EOT
    --data-folder: folder containing, per study, an optional {studyid}_sstr.tsv and a
    {ABV}_PatientMapping.v2.csv, plus one shared consents.csv. Local path or s3:// URI.
  EOT
}

variable "batch_size" {
  type        = string
  default     = "1000"
  description = "--batch-size: rows per batch insert"
}

variable "container_assume_role_arn" {
  type        = string
  default     = ""
  description = "Cross-account IAM role ARN for the container to assume when accessing S3 inputs."
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

variable "study_filter" {
  type        = string
  default     = ""
  description = "--study-filter: comma-separated study ids to process; blank processes every ready study"
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
