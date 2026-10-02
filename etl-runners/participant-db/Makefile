# SHARED: the participant database every DB-backed runner reads and writes.
#
# Unlike a runner, this stack has ONE state per environment (not per run): start writes it,
# stop finds it there. See docs/PARTICIPANT_DB.md.
#
# Locally:
#   export TF_VAR_run_id=local-1            # (TF_VAR_restore_from=none for a fresh schema)
#   make init apply wait-ready              # start
#   make init backup PROMOTE=false destroy  # stop, keeping LATEST where it was
NAME := participant-db

ENV       ?= development
STATE_KEY ?= tf_backend/etl-runners/hpds-etl/participant-db/$(ENV)/terraform.tfstate

include ../common.mk

# Scopes resource names and the backup prefix (<backup_s3_prefix>/<env>/backups/).
export TF_VAR_env_name = $(ENV)

READY_TIMEOUT_SECONDS  ?= 3600
BACKUP_TIMEOUT_SECONDS ?= 7200
PROMOTE                ?= true

.PHONY: wait-ready backup

# Blocks until the instance reports ready (exit 0) or failed / timed out (non-zero).
wait-ready:
	@./wait-ready.sh \
		"$$($(TF) -chdir=$(TF_DIR) output -raw instance_id)" \
		"$$($(TF) -chdir=$(TF_DIR) output -raw status_s3_uri)" \
		"$$($(TF) -chdir=$(TF_DIR) output -raw log_s3_uri)" \
		"$(READY_TIMEOUT_SECONDS)"

# pg_dump to S3 over SSM; PROMOTE=true also moves LATEST to the new dump.
backup:
	@./backup-via-ssm.sh \
		"$$($(TF) -chdir=$(TF_DIR) output -raw instance_id)" \
		"$(PROMOTE)" \
		"$(BACKUP_TIMEOUT_SECONDS)" \
		"$$($(TF) -chdir=$(TF_DIR) output -raw backup_s3_uri)"
