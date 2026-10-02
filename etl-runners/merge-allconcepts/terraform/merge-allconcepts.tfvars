# Runner-specific settings for merge-allconcepts.
# Shared infra (account, region, VPC, security group, instance role, DB secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# instance_type is NOT set here: a -var-file value outranks TF_VAR_instance_type, which would
# silently override the Jenkins INSTANCE_TYPE parameter. Its default lives in variables.tf.
root_volume_size = 30

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
