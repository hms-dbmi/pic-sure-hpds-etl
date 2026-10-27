# --- Infrastructure (from environments/<ENV>.tfvars and adapt-allconcepts-csvloader.tfvars) ---

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
  default     = "c5.xlarge"
  description = "Instance type. The job streams every file of the manifest three times at most (the global file twice) and computes MD5, so it is bound by sustained network and disk throughput."
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
  description = "Subnet to launch the runner in. Must reach S3. Blank = looked up from vpc_id."
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
  default     = 500
  description = "Root EBS size in GiB. The job builds its whole output (every partition file) on this volume before uploading, so it must hold it."
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

variable "manifest_uri" {
  type        = string
  description = "--manifest: s3:// URI of allConcepts_manifest.csv (versioned bucket)"
}

variable "output_uri" {
  type        = string
  description = "--output: EMPTY s3:// prefix that receives the split layout; never under avillach-73-bdcatalyst-etl"
}

variable "studies" {
  type        = string
  default     = ""
  description = "--studies: comma-separated study ids to adapt. Blank adapts every study of the manifest."
}

variable "max_reject_fraction" {
  type        = string
  default     = "0"
  description = "--max-reject-fraction: share (0-1) of the input rows that may be rejected"
}

variable "allow_consent_codes" {
  type        = string
  default     = ""
  description = "--allow-consent-codes: comma-separated consent codes accepted although they are not positive integers"
}

variable "multi_study_ids" {
  type        = string
  default     = "copy"
  description = "--multi-study-ids: copy | refuse"

  validation {
    condition     = contains(["copy", "refuse"], var.multi_study_ids)
    error_message = "multi_study_ids must be 'copy' or 'refuse'."
  }
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
