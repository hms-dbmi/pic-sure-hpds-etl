# Shared build/deploy targets for every ephemeral hpds-etl runner.
#
# Include from a runner's Makefile after setting NAME:
#   NAME := participants-migration
#   include ../common.mk
#
# Job parameters are NOT passed on the command line -- they arrive as TF_VAR_* environment
# variables, which Terraform picks up natively. That keeps this file identical for every
# runner and keeps values out of `ps` output and shell history.
#
# NOTE FOR JENKINS: make reports 2 for ANY failed recipe, so it cannot carry the ETL exit
# code (0/2/3/4/5) or the validators' "10 = warnings" out to the caller. The Jenkinsfiles
# therefore invoke monitor-runner.sh, preflight.sh, and validate.sh directly. The `monitor`
# and `validate` targets here are for local runs, where pass/fail is all you need.
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

REPO_ROOT   := $(abspath ../..)
COMMON_DIR  := $(abspath ../common)
TF          := terraform
TF_DIR      := terraform
TFVARS      := $(TF_DIR)/$(NAME).tfvars
BACKEND     := $(TF_DIR)/$(NAME).backend.tfvars

# Environment: switch with ENV=staging, ENV=production, etc.
ENV         ?= integration
ENV_TFVARS  := $(abspath ../environments/$(ENV).tfvars)

JAR         := $(REPO_ROOT)/target/hpds-etl.jar
IMAGE_TAR   ?= hpds-etl-runner.tar.gz
IMAGE_NAME  := $(basename $(basename $(notdir $(IMAGE_TAR))))

# Single source of truth for the bucket: check environment file first, then runner tfvars.
STACK_S3_BUCKET ?= $(or $(shell sed -n 's/^stack_s3_bucket[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' $(ENV_TFVARS) 2>/dev/null),$(shell sed -n 's/^stack_s3_bucket[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' $(TFVARS)))
AWS_REGION      ?= $(or $(shell sed -n 's/^aws_region[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' $(ENV_TFVARS) 2>/dev/null),$(shell sed -n 's/^aws_region[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' $(TFVARS)))
S3_IMAGE_URI    := s3://$(STACK_S3_BUCKET)/etl-runner/container/$(IMAGE_TAR)

# Per-run Terraform state. Jenkins overrides STATE_KEY so concurrent runs never share
# state; the default is the single-run-at-a-time path.
STATE_KEY   ?= tf_backend/etl-runners/hpds-etl/$(NAME)/terraform.tfstate

REPORTS_DIR ?= $(CURDIR)/reports
SKIP_TESTS  ?= false

.PHONY: help jar image image-save image-upload package init plan apply run monitor \
        fetch-reports output destroy clean validate-tf

help:
	@echo "Runner: $(NAME)"
	@echo ""
	@echo "Build:"
	@echo "  jar            - ./mvnw package at the repo root (SKIP_TESTS=true to skip)"
	@echo "  image          - docker build the runner image from the repo root"
	@echo "  package        - image + save + upload the tarball to S3"
	@echo ""
	@echo "Deploy / run:"
	@echo "  init           - terraform init (STATE_KEY=$(STATE_KEY))"
	@echo "  plan           - terraform plan"
	@echo "  apply          - terraform apply (creates the ephemeral instance)"
	@echo "  monitor        - wait for the run; exits with the JOB's exit code"
	@echo "  run            - apply + monitor"
	@echo "  fetch-reports  - sync the run's reports from S3 to $(REPORTS_DIR)"
	@echo "  output         - terraform outputs"
	@echo ""
	@echo "Teardown:"
	@echo "  destroy/clean  - terraform destroy (auto-approved)"
	@echo ""
	@echo "Required TF_VAR_* environment: TF_VAR_run_id (plus this runner's job params)"

# --- Build ---------------------------------------------------------------

jar:
	cd $(REPO_ROOT) && ./mvnw -B clean package $(if $(filter true,$(SKIP_TESTS)),-DskipTests,)

image:
	@test -f $(JAR) || { echo "ERROR: $(JAR) not found -- run 'make jar' first"; exit 1; }
	cd $(REPO_ROOT) && docker build --platform linux/amd64 \
		-f etl-runners/Dockerfile -t $(IMAGE_NAME) .

image-save: image
	docker save $(IMAGE_NAME) | gzip > $(IMAGE_TAR)

image-upload: image-save
	aws s3 cp $(IMAGE_TAR) $(S3_IMAGE_URI) --region $(AWS_REGION) --no-progress
	@echo "Uploaded $(S3_IMAGE_URI)"

package: image-upload

# --- Terraform -----------------------------------------------------------

init:
	$(TF) -chdir=$(TF_DIR) init -reconfigure \
		-backend-config=$(notdir $(BACKEND)) \
		-backend-config="key=$(STATE_KEY)"

validate-tf:
	$(TF) -chdir=$(TF_DIR) validate

plan:
	$(TF) -chdir=$(TF_DIR) plan -var-file=$(ENV_TFVARS) -var-file=$(notdir $(TFVARS))

apply:
	$(TF) -chdir=$(TF_DIR) apply -var-file=$(ENV_TFVARS) -var-file=$(notdir $(TFVARS)) --auto-approve

output:
	$(TF) -chdir=$(TF_DIR) output

# --- Run -----------------------------------------------------------------

# Exits with the job's own ExitCode so the caller can branch on 2/3/4/5.
monitor:
	@$(COMMON_DIR)/monitor-runner.sh \
		"$$($(TF) -chdir=$(TF_DIR) output -raw instance_id)" \
		"$$($(TF) -chdir=$(TF_DIR) output -raw status_s3_uri)" \
		"$$($(TF) -chdir=$(TF_DIR) output -raw log_s3_uri)"

run: apply monitor

fetch-reports:
	@mkdir -p $(REPORTS_DIR)
	aws s3 sync "$$($(TF) -chdir=$(TF_DIR) output -raw reports_s3_uri)" $(REPORTS_DIR) \
		--region $(AWS_REGION) --no-progress
	@ls -l $(REPORTS_DIR)

# --- Teardown ------------------------------------------------------------

destroy:
	$(TF) -chdir=$(TF_DIR) destroy -var-file=$(ENV_TFVARS) -var-file=$(notdir $(TFVARS)) --auto-approve

clean: destroy
	rm -f $(IMAGE_TAR)
