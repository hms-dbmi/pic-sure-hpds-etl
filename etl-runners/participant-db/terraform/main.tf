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
  #   participant_db_<UTC timestamp>.tar   one per participant-db-stop
  #   LATEST                               file name of the dump the next start restores
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
# The secret is created empty. The instance generates the password itself and writes the
# secret value (host/port/dbname/username/password) only once Postgres is restored and
# accepting connections, so the password never enters Terraform state or user data, and
# "the secret has a current value" doubles as "the database is ready". Destroyed with the
# stack; recovery_window_in_days = 0 so the next start can recreate the same name at once.
resource "aws_secretsmanager_secret" "db" {
  name                    = var.db_secret_id
  description             = "Temporary credentials for ${local.name}. Exists only while the database is up."
  recovery_window_in_days = 0
  tags                    = local.tags
}

# Grants the shared instance role access to this secret for as long as the database exists:
# the DB instance writes it, the runners and the agent read it. Removed by destroy.
resource "aws_iam_role_policy" "db_secret" {
  name = "${local.name}-secret"
  role = var.iam_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret",
        "secretsmanager:PutSecretValue",
      ]
      Resource = [aws_secretsmanager_secret.db.arn]
    }]
  })
}

# --- Network ----------------------------------------------------------------
#
# Postgres is reachable only from the runners' security groups. Its own group (rather than a
# rule on the shared one) so destroy leaves the shared group exactly as it found it.
resource "aws_security_group" "db" {
  name        = local.name
  description = "Postgres for ${local.name}, from the ETL runner security groups only"
  vpc_id      = data.aws_vpc.selected.id
  tags        = local.tags

  ingress {
    description     = "Postgres from ETL runners"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.vpc_security_group_ids
  }

  egress {
    description = "S3, Secrets Manager, SSM, package mirrors"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# --- Instance ---------------------------------------------------------------

resource "aws_iam_instance_profile" "db" {
  name = "${local.name}-profile"
  role = var.iam_role_name
  tags = local.tags
}

resource "aws_instance" "db" {
  ami                    = data.aws_ami.base.id
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  vpc_security_group_ids = concat([aws_security_group.db.id], var.vpc_security_group_ids)
  iam_instance_profile   = aws_iam_instance_profile.db.name

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
    db_secret_id      = aws_secretsmanager_secret.db.id
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

  # The instance writes the secret at the end of boot; the grant must exist first.
  depends_on = [aws_iam_role_policy.db_secret]
}
