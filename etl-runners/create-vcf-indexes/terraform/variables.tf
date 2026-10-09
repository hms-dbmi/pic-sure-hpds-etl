# --- Infrastructure (from create-vcf-indexes.tfvars) ----------------

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
  description = "Instance type. The job holds index data in memory; m5.large covers most deployments."
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
  description = "Root EBS size in GiB. Holds the container image and the index output."
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

variable "output_uri" {
  type        = string
  description = "--output: where vcfIndex.tsv and SampleIds.csv are written (local path or s3:// URI)"
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

variable "managed_inputs_uri" {
  type        = string
  default     = ""
  description = "--managed-inputs: CSV listing studies to include (local path or s3:// URI). When blank the job falls back to etl.managed-inputs.uri from its config."
}

variable "include_processed" {
  type        = string
  default     = ""
  description = "--include-processed: when 'true', studies marked Data Processed are included (migration pipeline)"
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
