# Runner-specific settings for participants-migration.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# Memory-bound: a study's patient-mapping file and the shared consents.csv are both held
# in memory while they are joined.
instance_type    = "r6i.large"
root_volume_size = 50

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "migration"
  Temporary   = "true"
  Jira        = "ALS-12158"
}
