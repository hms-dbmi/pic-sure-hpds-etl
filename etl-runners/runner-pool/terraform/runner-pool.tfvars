# Pool-wide settings. Shared infra (account, region, VPC, security group, instance role) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.
#
# instance_type, root_volume_size and uses_participant_db are NOT set here: a -var-file value
# outranks TF_VAR_*, and each caller sizes its pool for its own job. Their defaults live in
# variables.tf.

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
}
