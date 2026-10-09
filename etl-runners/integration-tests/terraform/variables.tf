# --- Shared infrastructure (environments/<ENV>.tfvars) -----------------------

variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "aws_account_id" {
  type        = string
  description = "Account the environment lives in; the provider refuses any other"
}

variable "stack_s3_bucket" {
  type        = string
  description = "Bucket holding each build's source zip and test reports (etl-runner/codebuild/ prefix)"
}

variable "iam_role_name" {
  type        = string
  default     = "bdc-etl-jenkins-role"
  description = "The role Jenkins runs as. Granted permission here to start, watch, and stop builds of this project."
}

# --- Project (integration-tests.tfvars) ---------------------------------------

variable "project_name" {
  type        = string
  default     = "hpds-etl-integration-tests"
  description = "CodeBuild project name. run-codebuild.sh assumes this default (override with CODEBUILD_PROJECT)."
}

variable "build_image" {
  type        = string
  default     = "aws/codebuild/amazonlinux-x86_64-standard:5.0"
  description = "CodeBuild-managed image. The buildspec installs Corretto 25 itself, so any image with Docker works."
}

variable "compute_type" {
  type        = string
  default     = "BUILD_GENERAL1_MEDIUM"
  description = "Build size. MEDIUM (4 vCPU / 7 GB) fits Maven plus the Postgres and LocalStack containers."
}

variable "build_timeout_minutes" {
  type        = number
  default     = 60
  description = "CodeBuild stops a build that runs longer than this"
}

variable "log_retention_days" {
  type        = number
  default     = 30
  description = "How long build logs are kept in CloudWatch"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}
