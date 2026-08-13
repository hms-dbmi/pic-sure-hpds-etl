terraform {
  # Values come from -backend-config=sstr-populate-rds-participants.backend.tfvars, with the
  # state key overridden per run (-backend-config="key=..."). The permanent pipeline can
  # load several studies in one build, so per-run state is required, not optional.
  backend "s3" {}

  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}
