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
  description = "Bucket holding run logs and the boot sentinel (etl-runner/ prefix)"
}

variable "ami_owner_id" {
  type        = string
  description = "AMI owner account id"
}

variable "ami_name_pattern" {
  type        = string
  description = "Glob selecting the most recent matching AMI (SRCE RHEL9 golden: PostgreSQL from module streams)"
}

variable "iam_role_name" {
  type        = string
  default     = "bdc-etl-jenkins-role"
  description = "Instance profile role. Must read/write the backup prefix and use SSM; secret access is granted here."
}

variable "vpc_id" {
  type        = string
  default     = ""
  description = "VPC to launch in; its lowest-id subnet is used when subnet_id is blank"
}

variable "subnet_id" {
  type        = string
  default     = ""
  description = "Subnet to launch in. Blank = looked up from vpc_id, the same way the runners do."
}

variable "vpc_security_group_ids" {
  type        = list(string)
  description = "The runners' security groups: attached to the DB too, and the only sources allowed to reach 5432"
}

variable "db_secret_id" {
  type        = string
  description = "Name of the temporary Secrets Manager secret the runners read (created and destroyed by this stack)"
}

# --- Participant DB (participant-db.tfvars) ----------------------------------

variable "instance_type" {
  type        = string
  default     = "m5.xlarge"
  description = "DB instance type. Several runners may load concurrently; size for that, not for one."
}

variable "root_volume_size" {
  type        = number
  default     = 100
  description = "Root EBS size in GiB: holds the cluster plus one restore archive and one dump at a time."
}

variable "pg_version" {
  type        = string
  default     = "16"
  description = "PostgreSQL major version: the RHEL9 module stream postgresql:<NN>. Restore needs >= the dump's server version."
}

variable "max_connections" {
  type        = number
  default     = 300
  description = "Postgres max_connections. Each runner opens a Hikari pool of up to DB_POOL_SIZE (8); the migration's split stage runs one runner per study in parallel."
}

variable "db_name" {
  type        = string
  default     = "etl_db"
  description = "Database name"
}

variable "db_username" {
  type        = string
  default     = "hpds_etl"
  description = "Application role; owns the database and every restored object"
}

variable "db_schema" {
  type        = string
  default     = "etl"
  description = "Schema holding the tables; must match DB_SCHEMA in application.yml and the dump's --schema"
}

variable "backup_s3_bucket" {
  type        = string
  description = "Bucket holding the dumps"
}

variable "backup_s3_prefix" {
  type        = string
  description = "Prefix (no bucket, no slashes at either end) under which <env>/backups/ holds the dumps"
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional resource tags"
}

# --- Per-run (supplied by Jenkins / the Makefile as TF_VAR_*) -----------------

variable "env_name" {
  type        = string
  description = "Environment name (the ENV the Makefile was run with); scopes names and the backup prefix"
}

variable "run_id" {
  type        = string
  description = "Correlation id for this start; names the boot log and sentinel"
}

variable "restore_from" {
  type        = string
  default     = ""
  description = <<-EOT
    What to restore at boot. Blank: the dump named by <backups>/LATEST (a fresh schema from
    schema.sql if there is no LATEST). "none": a fresh schema even if dumps exist. Otherwise a
    dump file name under <backups>/, or a full s3:// URI to a tarred pg_dump --format=d dump.
  EOT

  validation {
    condition     = can(regex("^[A-Za-z0-9._/:+=@-]*$", var.restore_from))
    error_message = "restore_from may contain only safe path characters."
  }
}
