provider "aws" {
  region = var.aws_region
  # Refuse to touch any account but the environment's: a wrong-profile apply fails fast.
  allowed_account_ids = [var.aws_account_id]
}

# The participant database (participants / consents / samples) as a short-lived Postgres
# server: participant-db-start applies this stack at the beginning of a pipeline, restoring
# the latest pg_dump from S3; participant-db-stop dumps it back to S3 and destroys the stack
# at the end. Nothing here outlives the pipeline except the dumps. See docs/PARTICIPANT_DB.md.

locals {
  name = "hpds-etl-participant-db-${var.env_name}"

  subnet_id = var.subnet_id != "" ? var.subnet_id : sort(data.aws_subnets.vpc[0].ids)[0]

  # Where dumps live: s3://<backup_s3_bucket>/<backup_s3_prefix>/<env>/backups/
  #   participant_db.tar               the restore point; each promoted stop overwrites it
  #   participant_db_not_promoted.tar  the last failed run's dump
  # The bucket is versioned: earlier dumps are older versions of these two keys.
  backup_key_prefix = "${var.backup_s3_prefix}/${var.env_name}/backups"

  # Boot sentinel and log, next to the runners' own (s3://<stack>/etl-runner/...).
  status_key = "etl-runner/participant-db/${var.env_name}/${var.run_id}/status.json"
  log_key    = "etl-runner/logs/participant-db-${var.env_name}-${var.run_id}.log"

  tags = merge({
    Name        = local.name
    Project     = "PIC-SURE HPDS ETL"
    Component   = "participant-db"
    Environment = var.env_name
    RunId       = var.run_id
    ManagedBy   = "terraform"
  }, var.tags)
}

data "aws_ami" "base" {
  most_recent = true
  owners      = [var.ami_owner_id]
  filter {
    name   = "name"
    values = [var.ami_name_pattern]
  }
}

data "aws_subnets" "vpc" {
  count = var.subnet_id == "" ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }
}

data "aws_subnet" "selected" {
  id = local.subnet_id
}

data "aws_vpc" "selected" {
  id = data.aws_subnet.selected.vpc_id
}

# --- Credentials ------------------------------------------------------------
#
# None here. The instance generates the password at boot and writes the connection details to
# a root-only file on itself (/etc/participant-db/credentials.json) once Postgres is restored,
# so the password never enters Terraform state or user data. participant-db-start copies that
# file to the Jenkins server over SSM (fetch-credentials.sh); runners get it from there.

# --- Network ----------------------------------------------------------------
#
# None created here: the account does not allow creating security groups at runtime. The DB
# attaches the runners' shared group (vpc_security_group_ids), which carries a standing rule
# admitting TCP 5432 from itself -- so runners, and anything else in that group, can reach
# Postgres.

# --- Instance ---------------------------------------------------------------

# Only when no pre-existing profile is named: this account does not allow creating instance
# profiles at runtime, so environments set iam_instance_profile_name.
resource "aws_iam_instance_profile" "db" {
  count = var.iam_instance_profile_name == "" ? 1 : 0
  name  = "${local.name}-profile"
  role  = var.iam_role_name
  tags  = local.tags
}

resource "aws_instance" "db" {
  ami                    = data.aws_ami.base.id
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_instance_profile   = var.iam_instance_profile_name != "" ? var.iam_instance_profile_name : aws_iam_instance_profile.db[0].name

  # NOT terminate-on-shutdown, unlike the runners: between a successful start and a
  # successful stop, the instance holds the only copy of the pipeline's writes. An
  # accidental shutdown must leave the volume recoverable.
  instance_initiated_shutdown_behavior = "stop"

  user_data_replace_on_change = true

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_size           = var.root_volume_size
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  # gzipped (cloud-init inflates it): the rendered script embeds schema.sql and the backup
  # script, which would otherwise sit close to EC2's 16 KiB user-data limit.
  user_data_base64 = base64gzip(templatefile("${path.module}/user_data.sh.tpl", {
    aws_region        = var.aws_region
    env_name          = var.env_name
    run_id            = var.run_id
    stack_s3_bucket   = var.stack_s3_bucket
    status_key        = local.status_key
    log_key           = local.log_key
    backup_s3_bucket  = var.backup_s3_bucket
    backup_key_prefix = local.backup_key_prefix
    restore_from      = var.restore_from
    db_name           = var.db_name
    db_username       = var.db_username
    db_schema         = var.db_schema
    pg_version        = var.pg_version
    max_connections   = var.max_connections
    vpc_cidr          = data.aws_vpc.selected.cidr_block
    schema_sql_b64    = filebase64("${path.module}/../../../src/main/resources/repository/schema.sql")
    backup_script_b64 = base64encode(templatefile("${path.module}/backup.sh.tpl", {
      aws_region        = var.aws_region
      backup_s3_bucket  = var.backup_s3_bucket
      backup_key_prefix = local.backup_key_prefix
      db_name           = var.db_name
      db_schema         = var.db_schema
    }))
  }))

  tags        = local.tags
  volume_tags = local.tags
}
