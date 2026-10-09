# SHARED: the CodeBuild project the orchestrators run the Testcontainers *IT suites in, so the
# Jenkins agent needs no container runtime.
#
# Unlike a runner, this stack has ONE state per environment (not per run): it is applied once
# by someone allowed to create IAM roles, and every pipeline run then starts builds in it.
# See docs/JENKINS.md#3-integration-tests-in-codebuild.
#
#   make init plan                 # review
#   make init apply                # create / update the project
#   make verify RUN_TAG=local-1    # run the suites for the current HEAD
NAME := integration-tests

ENV       ?= development
STATE_KEY ?= tf_backend/etl-runners/hpds-etl/integration-tests/$(ENV)/terraform.tfstate

include ../common.mk

RUN_TAG ?= local-$(shell date +%Y%m%d%H%M%S)

.PHONY: verify

verify:
	@./run-codebuild.sh $(ENV) $(RUN_TAG)
