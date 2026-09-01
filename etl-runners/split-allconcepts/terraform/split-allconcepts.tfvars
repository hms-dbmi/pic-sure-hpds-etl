# Runner-specific settings for split-allconcepts.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

instance_type    = "r6i.large"
root_volume_size = 50

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "migration"
  Temporary   = "true"
  Jira        = "ALS-12161"
}
