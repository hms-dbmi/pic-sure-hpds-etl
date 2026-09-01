# Shared infrastructure for the INTEGRATION environment.
#
# Every ephemeral runner loads this file alongside its own .tfvars so that
# account, network, and credential settings live in one place. Switch
# environments by passing ENV=<name> to make (default: integration).

aws_region       = "us-east-1"
stack_s3_bucket  = "avillach-biodatacatalyst-deployments-3drb48r"
ami_owner_id     = "amazon"
ami_name_pattern = "al2023-ami-2023.*-x86_64"

# Network
subnet_id              = "subnet-00a35d901a151ab01"
vpc_security_group_ids = ["sg-0a1231bb668b1428f"]

# RDS credentials (fetched by the runner at boot via Secrets Manager)
rds_secret_id        = "arn:aws:secretsmanager:us-east-1:900561893673:secret:rds!db-8086eb77-cd6d-48bc-9298-4380892798ca-9cjLb2"
manage_secret_access = true
rds_secret_arn       = "arn:aws:secretsmanager:us-east-1:900561893673:secret:rds!db-8086eb77-cd6d-48bc-9298-4380892798ca-9cjLb2"

# RDS connection details (the RDS-managed secret only contains username/password)
rds_host   = "dictionarydb.cljahwnkfisu.us-east-1.rds.amazonaws.com"
rds_dbname = "etl_db"
