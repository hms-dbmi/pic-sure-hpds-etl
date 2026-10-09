# Settings for the integration-test CodeBuild project. Shared infra (account, region, bucket,
# Jenkins role) comes from environments/<ENV>.tfvars, loaded by common.mk.

tags = {
  Project  = "PIC-SURE HPDS ETL"
  Pipeline = "shared"
}
