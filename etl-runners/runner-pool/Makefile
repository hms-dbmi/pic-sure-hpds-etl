# A pool of long-lived hpds-etl worker instances. A pipeline stage provisions it once, hands each
# worker one study at a time through its S3 mailbox (../common/pool-dispatch.sh), and destroys it
# at the end. Each worker boots, loads the runner image and reads the participant DB credentials
# once, so studies after the first start in seconds instead of after a full boot.
#
# Parameters arrive as TF_VAR_* environment variables:
#   TF_VAR_run_id          required   pool id; mailboxes live under etl-runner/pools/<run_id>/
#   TF_VAR_pool_job        required   job the workers run, e.g. split-allconcepts (names and tags)
#   TF_VAR_pool_size       required   number of workers
#   TF_VAR_image_tar       required   runner image tarball under etl-runner/container/
NAME := runner-pool

include ../common.mk
