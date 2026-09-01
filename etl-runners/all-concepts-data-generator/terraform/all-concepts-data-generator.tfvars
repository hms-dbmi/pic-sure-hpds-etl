# Runner-specific settings for all-concepts-data-generator.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

instance_type    = "m5.xlarge"
root_volume_size = 50

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
