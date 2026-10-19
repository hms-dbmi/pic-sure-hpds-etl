# runner-pool

A pool of long-lived hpds-etl worker instances — the
[etl-runner module's worker mode](../../terraform-modules/etl-runner/README.md#worker-mode) — that
runs any job, one run per worker at a time. A pipeline stage provisions the pool once, hands each
worker a run through its S3 mailbox, and destroys the pool at the end. Each worker boots, loads
the runner image and (with `uses_participant_db`) reads the participant DB credentials once, so
every run after its first starts in seconds instead of after a full boot.

> **Status: available, not yet used by the permanent pipeline.** Its jobs still run one instance
> per run. Moving work onto a pool (parallelising it) is a later improvement.

## Lifecycle

```bash
export ENV=development
export TF_VAR_run_id=jenkins-mypool-42 TF_VAR_pool_job=my-job TF_VAR_pool_size=4 \
       TF_VAR_image_tar=hpds-etl-runner-42.tar.gz
# Optional: TF_VAR_uses_participant_db=true TF_VAR_instance_type=r6i.large \
#           TF_VAR_root_volume_size=100 TF_VAR_idle_timeout_seconds=1800 TF_VAR_name_suffix=42
make init apply STATE_KEY=tf_backend/etl-runners/hpds-etl/runner-pool/$TF_VAR_run_id.tfstate

TF=$(make -s tf-path)
MAILBOX=$($TF -chdir=terraform output -raw mailbox_s3_prefix)
WORKER=$($TF -chdir=terraform output -json instance_ids | jq -r '.[0]')

# One run on one worker. Prints STATUS_URI=, REPORTS_URI=, LOG_URI= for that run.
../common/pool-dispatch.sh "$WORKER" "$MAILBOX" my-job my-job jenkins-my-job-42-a study_id=phs000001
# ...then ../common/monitor-runner.sh "$WORKER" "$STATUS_URI" "$LOG_URI", exactly as for a single runner.

# A run that timed out may still be going on its worker: retire the worker, don't reuse it.
../common/pool-retire-worker.sh "$WORKER" "run jenkins-my-job-42-a timed out"

make destroy STATE_KEY=...
```

`pool-dispatch.sh` refuses to write a task while the worker still has an unclaimed one, so a
caller dispatches a worker's next run only after the previous run's `status.json` exists.

## Inputs (TF_VAR_*)

| Name                   | Required | Default    | Meaning                                                                 |
|------------------------|:--------:|------------|-------------------------------------------------------------------------|
| `run_id`               | yes      |            | Pool id; mailboxes under `etl-runner/pools/<run_id>/`                    |
| `pool_job`             | yes      |            | Names/tags the workers and their logs; tasks name their own job         |
| `pool_size`            | yes      |            | Workers, 1–50                                                           |
| `image_tar`            | yes      |            | Runner image tarball under `etl-runner/container/`                      |
| `uses_participant_db`  | no       | `false`    | Read the participant DB credentials from SSM at boot                    |
| `instance_type`        | no       | `m5.large` | Size for the heaviest job the pool will run                             |
| `root_volume_size`     | no       | `50`       | GiB per worker                                                          |
| `idle_timeout_seconds` | no       | `1800`     | Idle worker self-terminates (backstop if the pipeline dies)            |
| `name_suffix`          | no       | `""`       | Keeps AWS resource names unique per pool                                |
| `java_opts`, `log_level` | no     |            | Container JVM flags, log level                                          |

`environment` comes from `ENV` (common.mk); network, account and role from
`environments/<ENV>.tfvars`.

## Outputs

| Name                   | Meaning                                                                 |
|------------------------|-------------------------------------------------------------------------|
| `instance_ids`         | Worker instance ids, in worker order                                    |
| `mailbox_s3_prefix`    | `s3://<bucket>/etl-runner/pools/<run_id>`: a worker reads `<this>/<instance-id>/task.json` |
| `worker_log_s3_prefix` | Each worker's own log lands at `<this>-<instance-id>.log` when it ends  |

## Tests

`make test` runs `../common/test/run-tests.sh`: `pool-dispatch.sh` and `pool-retire-worker.sh`
against a stubbed `aws`, plus the module's `user_data.sh.tpl` rendered by terraform (worker and
single mode) and run locally against stubbed `aws`/`podman`/IMDS — task runs, the env allow-list,
rejections, stop, idle timeout, termination mid-task, and the DB-credential phase. No AWS access
needed; the template tests are skipped without terraform on PATH.
