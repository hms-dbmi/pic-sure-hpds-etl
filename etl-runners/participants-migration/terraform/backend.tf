terraform {
  # Values come from -backend-config=participants-migration.backend.tfvars, with the state
  # key overridden per run (-backend-config="key=...") so concurrent runs never share state.
  backend "s3" {}

  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}
