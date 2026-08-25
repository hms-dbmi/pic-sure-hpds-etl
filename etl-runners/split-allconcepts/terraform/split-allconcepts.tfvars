# Infrastructure for the split-allconcepts ephemeral runner.
#
# Shares the same infrastructure parameters as participants-migration: same account, subnet,
# deployment bucket, and RDS secret.
#
# CONFIRM BEFORE THE FIRST RUN:
#   - subnet_id must have a route to the HPDS RDS instance and an S3 path (gateway
#     endpoint or NAT).
#   - rds_secret_id must exist in this region and hold the ETL user's credentials.
#   - jenkins-s3-role must have read access to the avillach-73-bdcatalyst-etl bucket
#     (allConcepts source files).

aws_region       = "us-east-1"
stack_s3_bucket  = "avillach-biodatacatalyst-deployments-3drb48r"
ami_owner_id     = "amazon"
ami_name_pattern = "al2023-ami-2023.*-x86_64"
subnet_id        = "subnet-00a35d901a151ab01"

instance_type    = "r6i.large"
root_volume_size = 50

rds_secret_id = "hpds/rds/etl-credentials"

manage_secret_access = false

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "migration"
  Temporary   = "true"
  Jira        = "ALS-12161"
}
