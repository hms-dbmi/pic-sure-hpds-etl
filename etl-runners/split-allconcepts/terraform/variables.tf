# --- Infrastructure (from split-allconcepts.tfvars) --------------------------

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
    Instance type. The job streams the allConcepts CSV to per-consent temp files
    on disk; only the id mapping and consent assignments live in memory, so the
    job is disk/network-bound, not memory-bound.
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
  default     = 500
  description = "Root EBS size in GiB. Holds the container image plus the per-consent temp spool, which totals roughly the input size (phs000200's legacy allConcepts is 34 GiB). Sized generously: the volume is ephemeral and gp3 is cheap, running out of disk mid-split is not."
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
  description = "Correlation id passed to the job as --run-id (Jenkins BUILD_TAG)"
}

variable "name_suffix" {
  type        = string
  default     = ""
  description = "Per-run suffix keeping AWS resource names unique (Jenkins BUILD_NUMBER)"
}

variable "study_id" {
  type        = string
  description = "--study-id: the phs###### study identifier"
}

variable "abbreviation" {
  type        = string
  description = "--abbreviation: study abbreviated name"
}

variable "input_uri" {
  type        = string
  description = <<-EOT
    --input: S3 URI of the study's allConcepts CSV, e.g.
    s3://avillach-73-bdcatalyst-etl/{abv_lower}/completed/{study_id}/{study_id}_allConcepts_new_search_with_data_analyzer.csv
  EOT
}

variable "mapping_uri" {
  type        = string
  description = "--mapping: S3 URI of the hpds_id_mapping.csv produced by participants-migration. Must be an s3:// URI readable by container_assume_role_arn (i.e. in the 73 bucket); the container resolves local paths against its own filesystem, where only /reports is mounted."
}

variable "output_uri" {
  type        = string
  description = <<-EOT
    --output: output directory for split files. Structure:
    {output}/split_allconcepts/{study_id}/c{code}/{ABV}_allConcepts_c{code}.csv
  EOT
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
