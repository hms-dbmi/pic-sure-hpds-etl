# Runner-specific settings for split-allconcepts.
# Shared infra (account, region, VPC, security group, instance role) comes from
# environments/<ENV>.tfvars, loaded automatically by common.mk.

# instance_type and root_volume_size are NOT set here: a -var-file value outranks TF_VAR_*, which
# would silently override the Jenkins INSTANCE_TYPE and ROOT_VOLUME_GB parameters (the migration
# orchestrator sizes both for large studies). Their defaults live in variables.tf.

tags = {
  Project     = "PIC-SURE HPDS ETL"
  Environment = "etl"
  Pipeline    = "migration"
  Temporary   = "true"
  Jira        = "ALS-12161"
}
