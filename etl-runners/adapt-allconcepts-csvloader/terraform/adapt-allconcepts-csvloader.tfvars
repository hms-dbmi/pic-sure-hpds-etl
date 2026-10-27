# Runner-specific settings for adapt-allconcepts-csvloader.
# Shared infra (account, region, VPC, security group, instance role) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# Nothing that Jenkins sets as a TF_VAR_* belongs here: a -var-file value outranks TF_VAR_*, so it would
# silently override the build parameter. That covers instance_type (INSTANCE_TYPE), root_volume_size
# (ROOT_VOLUME_GB) and every job parameter; their defaults live in variables.tf.

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "temporary"
}
