# Infrastructure for the sstr-populate-rds-participants ephemeral runner.
#
# Region, stack bucket, AMI selector, and subnet are copied from the BDC ETL runners
# (bdc-etl-curation/biolincc/terraform/biolincc.tfvars) so both repos land their runners in
# the same account, subnet, and deployment bucket.
#
# CONFIRM BEFORE THE FIRST RUN:
#   - subnet_id must have a route to the HPDS RDS instance and an S3 path (gateway
#     endpoint or NAT). The BioLINCC runners do not talk to RDS, so this is the one
#     value their configuration cannot vouch for.
#   - rds_secret_id must exist in this region and hold the ETL user's credentials.

aws_region       = "us-east-1"
stack_s3_bucket  = "avillach-biodatacatalyst-deployments-3drb48r"
ami_owner_id     = "aws-marketplace"
ami_name_pattern = "*al2023*x86*64*LATEST*"
subnet_id        = "subnet-00a35d901a151ab01"

# One Telemetry record is held per input row, so the ceiling is the largest SSTR file, not
# the average. Override per study with INSTANCE_TYPE / the instance_type column in
# studies.tsv when a study needs more headroom.
instance_type    = "m5.large"
root_volume_size = 30

# Secrets Manager id (not the value). Expected JSON: {"url"|"host"/"port"/"dbname",
# "username", "password"} -- an RDS-managed secret works unchanged.
rds_secret_id = "hpds/rds/etl-credentials"

# Leave false: jenkins-s3-role is shared with the BDC pipelines and is managed centrally.
manage_secret_access = false
# rds_secret_arn     = "arn:aws:secretsmanager:us-east-1:<account>:secret:hpds/rds/etl-credentials-??????"

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
