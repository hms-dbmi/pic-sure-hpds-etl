# --- Infrastructure (from generate-allconcepts-manifest.tfvars) ----------------

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
  default     = "t3.medium"
  description = "Instance type. The job only lists and heads S3 objects; it reads no data files."
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
  description = "Instance profile role; the job does all S3 work as this role"
}

variable "iam_instance_profile_name" {
  type        = string
  default     = ""
  description = "Pre-existing instance profile to attach (environments/<ENV>.tfvars). Blank creates one around iam_role_name."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security group IDs to attach to the runner. When empty, the VPC default is used."
}

variable "root_volume_size" {
  type        = number
  default     = 30
  description = "Root EBS size in GiB."
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

variable "input_uri" {
  type        = string
  description = "--input: data root (s3://, versioned bucket) holding {study_id}/allConcepts/c{consent}/ folders; allConcepts_manifest.csv is written here"
}

variable "global_allconcepts_uri" {
  type        = string
  description = "--global-allconcepts: the global AllConcepts output (s3://, versioned bucket): global_AllConcepts.csv or its folder. Listed first in the manifest."
}

variable "managed_inputs_uri" {
  type        = string
  default     = ""
  description = "--managed-inputs: CSV whose ready studies the manifest lists (local path or s3:// URI). When blank the job falls back to etl.managed-inputs.uri from its config."
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
