# A pool of long-lived hpds-etl worker instances (the etl-runner module's worker mode), for any
# job. A pipeline provisions it once, hands each worker one run at a time through its S3 mailbox
# (../common/pool-dispatch.sh), and destroys it at the end. Each worker boots, loads the runner
# image and (with uses_participant_db) reads the participant DB credentials once, so runs after
# the first start in seconds instead of after a full boot. See README.md.
#
# AVAILABLE, NOT YET USED by the permanent pipeline: its jobs still run one instance per run.
#
# Parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id               required   pool id; mailboxes live under etl-runner/pools/<run_id>/
#   TF_VAR_pool_job             required   names and tags the workers; tasks name their own job
#   TF_VAR_pool_size            required   number of workers
#   TF_VAR_image_tar            required   runner image tarball under etl-runner/container/
#   TF_VAR_uses_participant_db  optional   true to give the workers the participant DB (default false)
#   TF_VAR_instance_type, TF_VAR_root_volume_size, TF_VAR_idle_timeout_seconds, TF_VAR_name_suffix
#                               optional
#
#   make test     tests for the pool scripts and the worker-mode bootstrap (stubbed; no AWS access)
NAME := runner-pool

include ../common.mk

.PHONY: test

test:
	@../common/test/run-tests.sh
