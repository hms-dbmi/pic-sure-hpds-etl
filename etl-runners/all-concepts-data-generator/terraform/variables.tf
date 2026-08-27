# --- Infrastructure (from all-concepts-data-generator.tfvars) ----------------

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
  default     = "m5.xlarge"
  description = "Instance type. Decoded data is read streaming but large studies benefit from more memory."
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
  description = "Root EBS size in GiB. Holds the container image plus decoded data and output CSVs."
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

# --- Per-run (supplied by Jenkins as TF_VAR_*) --------------------------------

variable "run_id" {
  type        = string
  description = "Correlation id passed to the job as --run-id"
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = "Per-run suffix keeping AWS resource names unique"
}

variable "study_id" {
  type        = string
  description = "--study-id: dbGaP study id (phs######)"
}

variable "data_dir" {
  type        = string
  description = "--data-dir: S3 prefix or local path containing decoded data CSVs"
}

variable "mapping_uri" {
  type        = string
  description = "--mapping: S3 URI or local path to the mapping CSV"
}

variable "output_uri" {
  type        = string
  description = "--output: output directory for per-consent allConcepts files"
}

variable "skip_analysis" {
  type        = string
  default     = "false"
  description = "--skip-analysis: skip data type re-analysis (true/false)"
}

variable "image_tar" {
  type        = string
  default     = "hpds-etl-runner.tar.gz"
  description = "Container tarball under s3://<stack_s3_bucket>/etl-runner/container/"
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
