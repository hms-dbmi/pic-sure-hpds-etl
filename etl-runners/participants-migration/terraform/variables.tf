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
  default     = 50
  description = "Root EBS size in GiB. Holds the container image plus the per-study mapping CSVs."
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
    --data-folder: root of the per-study folders. Per study: an optional
    {study_id}/rawData/sstr_{study_id}.{v}.txt and {study_id}/legacy/data/{ABV}_PatientMapping.v2.csv;
    plus the shared general/completed/GLOBAL_allConcepts_merged.csv. Local path or s3:// URI.
  EOT
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
