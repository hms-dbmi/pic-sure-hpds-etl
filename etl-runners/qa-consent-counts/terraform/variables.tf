# --- Infrastructure (from qa-consent-counts.tfvars) -----------------

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
  description = "Instance type. The job holds only per-consent counts; most of its time is spent waiting on dbGaP."
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
  description = "Subnet to launch the runner in. Must reach the participant DB, S3 and (HTTPS) www.ncbi.nlm.nih.gov. Blank = looked up from vpc_id."
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
  description = "Root EBS size in GiB. Holds the container image."
}

variable "environment" {
  type        = string
  description = "ETL environment (common.mk sets TF_VAR_environment from ENV). Names the participant DB credentials' SSM parameter, /hpds-etl/<environment>/participant-db/app-credentials."
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

variable "image_tar" {
  type        = string
  default     = "hpds-etl-runner.tar.gz"
  description = "Container tarball under s3://<stack_s3_bucket>/etl-runner/container/"
}

variable "managed_inputs_uri" {
  type        = string
  default     = ""
  description = "--managed-inputs: CSV whose ready studies are checked (local path or s3:// URI). Blank uses the job's configured etl.managed-inputs.uri."
}

variable "load_counts_uri" {
  type        = string
  default     = ""
  description = "--load-counts: this run's participants loaded per consent group (study_id,consent_code,participants_loaded[,source]). Blank skips the load-report check."
}

variable "study_filter" {
  type        = string
  default     = ""
  description = "--study-filter: comma-separated study ids to check. Blank checks every ready study."
}

variable "concurrency" {
  type        = string
  default     = ""
  description = "--concurrency: studies looked up in dbGaP at once (1-16). Blank: the job's default, 4."
}

variable "dbgap_base_url" {
  type        = string
  default     = ""
  description = "--dbgap-base-url: dbGaP SSTR study endpoint. Blank: https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study"
}

variable "dbgap_max_attempts" {
  type        = string
  default     = ""
  description = "--dbgap-max-attempts: requests per study, retries included (1-20). Blank: 3."
}

variable "dbgap_initial_backoff_seconds" {
  type        = string
  default     = ""
  description = "--dbgap-initial-backoff-seconds: first retry wait, doubling per retry (0-600). Blank: 60."
}

variable "dbgap_max_backoff_seconds" {
  type        = string
  default     = ""
  description = "--dbgap-max-backoff-seconds: longest retry wait (1-3600). Blank: 120."
}

variable "dbgap_timeout_seconds" {
  type        = string
  default     = ""
  description = "--dbgap-timeout-seconds: per-request timeout (1-600). Blank: 60."
}

variable "dbgap_give_up_after" {
  type        = string
  default     = ""
  description = "--dbgap-give-up-after: stop asking dbGaP after this many studies in a row got no answer (1-1000). Blank: 20."
}

variable "dbgap_deadline_seconds" {
  type        = string
  default     = ""
  description = "--dbgap-deadline-seconds: no lookup starts after this many seconds (1-86400). Blank: 5400."
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
