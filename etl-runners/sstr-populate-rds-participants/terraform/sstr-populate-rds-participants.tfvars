# Runner-specific settings for sstr-populate-rds-participants.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# One Telemetry record is held per input row, so the ceiling is the largest SSTR file, not
# the average. Override per study with INSTANCE_TYPE / the instance_type column in
# studies.tsv when a study needs more headroom.
instance_type    = "m5.large"
root_volume_size = 30

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
