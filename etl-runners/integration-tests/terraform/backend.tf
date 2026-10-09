terraform {
  # Values come from -backend-config=integration-tests.backend.tfvars, with the key overridden
  # per environment (-backend-config="key=..."). One state per environment, not per run: the
  # project is long-lived and every pipeline run starts builds in it.
  backend "s3" {}

  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}
