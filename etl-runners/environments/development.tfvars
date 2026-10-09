# Shared infrastructure for the DEVELOPMENT environment (account 515157839325).
#
# Every ephemeral runner, and the participant-db stack, loads this file alongside its own
# .tfvars so that account, network, and credential settings live in one place. Switch
# environments by passing ENV=<name> to make (default: development).
#
# Data lives in the same account as the runners, so no cross-account role is assumed: every
# instance does all S3, Secrets Manager, and SSM work as iam_role_name.

aws_region       = "us-east-1"
aws_account_id   = "515157839325"
stack_s3_bucket  = "bdc-etl-data-d0d6191"
# SRCE RHEL9 golden image, the same AMI the pheno ETL environment's runners and Jenkins host use
# (avillach-jenkins-bdc-etl: pipelines/hpds-ingest/terraform/hpds-ingest.tfvars). Its container
# runtime is podman; the bootstraps enable it through /opt/srce/startup.config.
ami_owner_id     = "752463128620"
ami_name_pattern = "srce-rhel9-golden*"

# Instance profile role for every ephemeral instance (runners and the participant DB).
iam_role_name = "bdc-etl-jenkins-role"

# Network. The private subnet the pheno environment's hpds-ingest runners use (it carries the
# subnet-type=private tag the pheno Jenkins host selects by). The runners and the participant DB
# must share it (or at least share routing) for the runners to reach Postgres.
vpc_id                 = "vpc-0fcb0b3dc2167e8b4"
subnet_id              = "subnet-03a30d72bd12478fc"
vpc_security_group_ids = ["sg-0932143f21f7c533b"]

# Participant database. Created by participant-db-start and destroyed by participant-db-stop;
# it exists (and holds host/port/dbname/username/password) only while the database is up.
db_secret_id = "hpds-etl-development-participant-db"
