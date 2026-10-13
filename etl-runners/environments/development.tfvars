# Shared infrastructure for the DEVELOPMENT environment (account 515157839325).
#
# Every ephemeral runner loads this file alongside its own .tfvars, and the participant-db scripts
# read aws_region and participant_db_host from it, so that account, network, and credential
# settings live in one place. Switch environments by passing ENV=<name> to make (default:
# development).
#
# Data lives in the same account as the runners, so no cross-account role is assumed: every
# instance does all S3 and SSM work as iam_role_name.

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

# Pre-existing instance profile every ephemeral instance attaches (the one the Jenkins server
# uses; it wraps iam_role_name). This account does not allow creating instance profiles at
# runtime. Blank would make each stack create its own around iam_role_name instead.
iam_instance_profile_name = "bdc-etl-jenkins-temp-profile"

# Network. The private subnet the pheno environment's hpds-ingest runners use (it carries the
# subnet-type=private tag the pheno Jenkins host selects by). The runners and the participant DB
# must share it (or at least share routing) for the runners to reach Postgres.
vpc_id                 = "vpc-0fcb0b3dc2167e8b4"
subnet_id              = "subnet-03a30d72bd12478fc"
vpc_security_group_ids = ["sg-0932143f21f7c533b"]

# The participant database runs as a container on the Jenkins host (docs/PARTICIPANT_DB.md).
# Runners connect to this address on port 5432: the Jenkins host's private IP or DNS name. Left
# unset, participant-db-start reads it from instance metadata, which a container reaches only
# when the instance's metadata hop limit is at least 2.
# participant_db_host = "10.x.x.x"
