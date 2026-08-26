# Infrastructure for the create-vcf-indexes ephemeral runner.
#
# Values match the other permanent runners so they all land in the same
# account, subnet, and deployment bucket.

aws_region       = "us-east-1"
stack_s3_bucket  = "avillach-biodatacatalyst-deployments-3drb48r"
ami_owner_id     = "amazon"
ami_name_pattern = "al2023-ami-2023.*-x86_64"
subnet_id        = "subnet-00a35d901a151ab01"

instance_type    = "m5.large"
root_volume_size = 30

rds_secret_id = "arn:aws:secretsmanager:us-east-1:900561893673:secret:rds!db-8086eb77-cd6d-48bc-9298-4380892798ca-9cjLb2"

manage_secret_access = true
rds_secret_arn       = "arn:aws:secretsmanager:us-east-1:900561893673:secret:rds!db-8086eb77-cd6d-48bc-9298-4380892798ca-9cjLb2"

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
