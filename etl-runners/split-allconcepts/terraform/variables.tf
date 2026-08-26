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
    Instance type. The job reads the allConcepts CSV, mapping CSV, and consent
    assignments into memory, so it is memory-bound.
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
  description = "Root EBS size in GiB. Holds the container image plus per-consent output CSVs."
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
  description = "--mapping: S3 URI of the hpds_id_mapping.csv produced by participants-migration"
}

variable "output_uri" {
  type        = string
  description = <<-EOT
    --output: output directory for split files. Structure:
    {output}/split_allconcepts/{study_id}/c{code}/{ABV}_allConcepts_c{code}.csv
  EOT
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
