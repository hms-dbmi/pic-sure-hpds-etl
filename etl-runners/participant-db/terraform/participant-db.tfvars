# Settings for the participant database. Shared infra (account, region, VPC, security group,
# instance role, secret name) comes from environments/<ENV>.tfvars, loaded by common.mk.
#
# instance_type is NOT set here: a -var-file value outranks TF_VAR_instance_type, which would
# silently override the Jenkins INSTANCE_TYPE parameter. Its default lives in variables.tf.

root_volume_size = 100
pg_version       = "16"

# Dumps: s3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/participant-db/<env>/backups/
backup_s3_bucket = "bdc-etl-data-d0d6191"
backup_s3_prefix = "avillach-73-bdcatalyst-etl/participant-db"

tags = {
  Project  = "PIC-SURE HPDS ETL"
  Pipeline = "shared"
}
