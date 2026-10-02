# Runner-specific settings for merge-allconcepts.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

instance_type    = "t3.medium"
root_volume_size = 30

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
