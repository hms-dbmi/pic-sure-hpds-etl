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
  description = "Root EBS size in GiB. Holds the container image and the mapping output."
}

variable "db_secret_id" {
  type        = string
  default     = ""
  description = "Participant DB secret (environments/<ENV>.tfvars). Unused: this job touches no database."
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
