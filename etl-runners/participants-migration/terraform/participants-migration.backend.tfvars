# Terraform state backend. `key` is a default only -- the Makefile always passes
# -backend-config="key=$(STATE_KEY)" after this file, and Jenkins sets STATE_KEY to a
# per-build path so two runs never share state.
bucket  = "bdc-etl-data-d0d6191"
key     = "tf_backend/etl-runners/hpds-etl/participants-migration/terraform.tfstate"
region  = "us-east-1"
encrypt = true
