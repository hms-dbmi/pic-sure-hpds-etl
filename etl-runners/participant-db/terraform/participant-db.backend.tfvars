# Terraform state backend. `key` is a default only -- the Makefile passes
# -backend-config="key=$(STATE_KEY)", and STATE_KEY is fixed per environment
# (tf_backend/etl-runners/hpds-etl/participant-db/<ENV>/terraform.tfstate).
bucket  = "bdc-etl-data-d0d6191"
key     = "tf_backend/etl-runners/hpds-etl/participant-db/development/terraform.tfstate"
region  = "us-east-1"
encrypt = true
