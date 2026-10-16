# Runner-specific settings for split-allconcepts-by-consent.
# Shared infra (region, subnet, security group, RDS secret) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.
#
# root_volume_size is deliberately NOT set here: a -var-file would override the Jenkins
# ROOT_VOLUME_GB parameter (TF_VAR_root_volume_size). The default is in variables.tf.

instance_type = "r6i.large"

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "permanent"
}
