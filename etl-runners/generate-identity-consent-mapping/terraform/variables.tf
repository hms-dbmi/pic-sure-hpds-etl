# --- Infrastructure (from generate-identity-consent-mapping.tfvars) ----------

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
  description = "Instance type. The job streams small TSVs; m5.large is comfortable headroom."
}

variable "subnet_id" {
  type        = string
  description = "Subnet to launch the runner in. Must have a route to S3."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the runner. When empty, the VPC default is used."
}

variable "root_volume_size" {
  type        = number
  default     = 30
  description = "Root EBS size in GiB. Holds the container image and the mapping output."
}

variable "rds_secret_id" {
  type        = string
  description = "Secrets Manager id holding the RDS credentials (unused by this job, required by the shared runner user-data)"
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

variable "base_uri" {
  type        = string
  default     = "s3://nih-nhlbi-bdc-harmdata-exchange"
  description = "--base: bucket/prefix holding the DMC harmonization drops"
}

variable "dataset_prefix" {
  type        = string
  default     = ""
  description = "--dataset-prefix: pin one drop (BDC-DMC-Harmonization-Examples-YYYYMMDD); blank selects the latest by date"
}

variable "input_role_arn" {
  type        = string
  description = "--role-arn: IAM role the job assumes in-process for all reads of the base"
}

variable "output_uri" {
  type        = string
  description = "--output: where the mapping CSV(s) are written (local path or s3:// URI)"
}

variable "per_study" {
  type        = string
  default     = ""
  description = "--per-study: when 'true', one CSV per study instead of a single combined file"
}

variable "container_assume_role_arn" {
  type        = string
  default     = ""
  description = "Cross-account IAM role ARN the container's default credential chain assumes (dbgap-etl; used for the output writes)."
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
