terraform {
  # Values come from -backend-config=participant-db.backend.tfvars, with the key overridden
  # per environment (-backend-config="key=..."). The key is FIXED per environment, not per
  # run: participant-db-stop must find the state participant-db-start wrote, and a start
  # refuses to run while that state still holds an instance.
  backend "s3" {}

  required_version = ">= 1.3.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}
