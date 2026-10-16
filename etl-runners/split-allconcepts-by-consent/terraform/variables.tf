# --- Infrastructure (from split-allconcepts-by-consent.tfvars) --------------------------

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
    Instance type. The job streams the allConcepts CSV twice and spools per-partition temp files
    to disk; only each patient's consent strings and per-partition patient sets live in memory,
    so it is disk/network-bound, not memory-bound.
  EOT
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
  default     = 1000
  description = "Root EBS size in GiB. The job spools every output byte to local disk before uploading, so this must be at least 2x the merged allConcepts input (more for globals=by-patient, which copies global rows into every consent partition). preflight.sh checks it against the input size. Overridden by Jenkins ROOT_VOLUME_GB via TF_VAR_root_volume_size."
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

variable "input_uri" {
  type        = string
  description = "--input: the merged allConcepts CSV, e.g. s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_merged/allConcepts_data_analyzer.csv"
}

variable "output_uri" {
  type        = string
  description = "--output: output prefix for {partition}-allConcepts.csv and split_manifest.json, e.g. s3://bdc-etl-data-d0d6191/data/transforms/all_concepts_split/<run id>/. Must not be under avillach-73-bdcatalyst-etl (the job refuses)."
}

variable "depth" {
  type        = number
  default     = 1
  description = "--depth: 0-based concept-path segment naming the study; the leading empty segment is depth 0."
}

variable "globals" {
  type        = string
  default     = "own"
  description = "--globals: own (each non-study node gets its own partition) or by-patient (copied into each of the patient's consent partitions)."
}

variable "fence_mapping" {
  type        = string
  default     = ""
  description = "--fence-mapping: fence_mapping.json for the report-only coverage cross-check, or none to skip. Blank uses the job default (the mirrored legacy metadata_new_search.json)."
}

variable "consents_path" {
  type        = string
  default     = ""
  description = "--consents-path: blank uses the job default \\_consents\\. Only set if the consents variable lives elsewhere."
}

variable "force" {
  type        = bool
  default     = false
  description = "--force: allow a non-empty output prefix (overwrites same-named files, never deletes). Jenkins sets this on infrastructure retries."
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
