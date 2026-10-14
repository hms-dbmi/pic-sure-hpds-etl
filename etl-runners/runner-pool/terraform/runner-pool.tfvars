# Pool-wide settings. Shared infra (account, region, VPC, security group, instance role) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# instance_type is NOT set here: a -var-file value outranks TF_VAR_instance_type. Its default lives
# in variables.tf. Same root volume as the single split runner.
root_volume_size = 50

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Temporary   = "true"
}
