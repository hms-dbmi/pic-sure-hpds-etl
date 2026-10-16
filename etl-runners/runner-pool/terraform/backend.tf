terraform {
  # Values come from -backend-config=runner-pool.backend.tfvars, with the state key overridden per
  # pool (-backend-config="key=...") so concurrent pools never share state.
  backend "s3" {}

  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}
