# Infrastructure for the generate-global-all-concepts ephemeral runner.
#
# Values match the sstr-populate-rds-participants runner so both land in the same
# account, subnet, and deployment bucket.

aws_region       = "us-east-1"
stack_s3_bucket  = "avillach-biodatacatalyst-deployments-3drb48r"
ami_owner_id     = "amazon"
ami_name_pattern = "al2023-ami-2023.*-x86_64"
subnet_id        = "subnet-00a35d901a151ab01"

instance_type    = "m5.large"
root_volume_size = 30

rds_secret_id = "hpds/rds/etl-credentials"

manage_secret_access = false

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
