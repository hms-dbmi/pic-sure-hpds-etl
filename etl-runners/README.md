# ETL Runners

One directory per hpds-etl job that runs on an ephemeral EC2 runner. Each directory holds the
job's Jenkinsfile, its Terraform module call, and its pre-flight and post-run validation.

Architecture, exit-code contract, AWS prerequisites, and the operations runbook are in
[`docs/JENKINS.md`](../docs/JENKINS.md).

## Table of Contents

- [Structure](#structure)
- [Shared Components](#shared-components)
- [Runner Contents](#runner-contents)
- [Make Targets](#make-targets)
- [Environment Variables](#environment-variables)
- [Creating a New Runner](#creating-a-new-runner)

---

## Structure

```
etl-runners/
├── Dockerfile                          One image for every job; the JAR selects the job at runtime
├── run-job.sh                          Container entrypoint
├── common.mk                           Shared build/deploy/monitor targets
├── common/                             Shared shell libraries
├── participants-migration/             TEMPORARY (JobType.MIGRATION)
└── sstr-populate-rds-participants/     PERMANENT (JobType.PERMANENT)
```

## Shared Components

| File                        | Purpose                                                                                                                                 |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `Dockerfile`                | Runtime image: Amazon Corretto plus `target/hpds-etl.jar`. Built from the repository root.                                              |
| `run-job.sh`                | Container entrypoint. Converts `ETL_PARAM_<key>` environment variables to `--kebab-case` flags, runs the JAR, exits with its exit code. |
| `common.mk`                 | Build, deploy, monitor, and teardown targets, included by each runner's `Makefile`.                                                     |
| `common/lib.sh`             | Assertion helpers: `check`, `soft`, `fail`, `warn`, `note`, `summary`.                                                                  |
| `common/monitor-runner.sh`  | Polls EC2 state and the `status.json` sentinel; exits with the job's own exit code.                                                     |
| `common/validate-report.sh` | Assertions true of every `JobResult` report, independent of which job produced it.                                                      |

Job parameters are passed as environment variables rather than an argv string so that the
generated EC2 user-data never has to quote a command line.

## Runner Contents

| File           | Purpose                                                                                    |
|----------------|--------------------------------------------------------------------------------------------|
| `Jenkinsfile`  | The job's own pipeline: build, pre-flight, package, provision, monitor, validate, destroy. |
| `Makefile`     | Sets `NAME` and includes `../common.mk`; adds `preflight` and `validate` targets.          |
| `preflight.sh` | Input-layout checks that run before any instance is provisioned.                           |
| `validate.sh`  | Assertions over the JSON report after the run.                                             |
| `terraform/`   | Module call, `<name>.tfvars`, `<name>.backend.tfvars`, outputs.                            |
| `studies.tsv`  | SSTR only: the study manifest driving a full sweep.                                        |

## Make Targets

Defined in `common.mk` unless noted.

| Target          | Description                                                               |
|-----------------|---------------------------------------------------------------------------|
| `help`          | List targets for this runner (default goal)                               |
| `jar`           | `./mvnw clean package` at the repository root (`SKIP_TESTS=true` to skip) |
| `image`         | `docker build` the runner image from the repository root                  |
| `image-save`    | `docker save` the image to `$(IMAGE_TAR)`                                 |
| `image-upload`  | Upload the tarball to `s3://<stack>/etl-runner/container/`                |
| `package`       | `image` + `image-save` + `image-upload`                                   |
| `init`          | `terraform init -reconfigure` with the backend config and `STATE_KEY`     |
| `validate-tf`   | `terraform validate`                                                      |
| `plan`          | `terraform plan`                                                          |
| `apply`         | `terraform apply --auto-approve`; creates the ephemeral instance          |
| `monitor`       | Wait for the run to finish                                                |
| `run`           | `apply` + `monitor`                                                       |
| `fetch-reports` | `aws s3 sync` the run's reports into `$(REPORTS_DIR)`                     |
| `output`        | `terraform output`                                                        |
| `destroy`       | `terraform destroy --auto-approve`                                        |
| `clean`         | `destroy` plus removal of the local image tarball                         |
| `preflight`     | Per-runner: input-layout checks (defined in the runner's `Makefile`)      |
| `validate`      | Per-runner: report assertions (defined in the runner's `Makefile`)        |

`make` reports 2 for any failed recipe, so it cannot carry the ETL exit code or the validators'
`10` warning signal. The Jenkinsfiles invoke `monitor-runner.sh`, `preflight.sh`, and
`validate.sh` directly; the `monitor` and `validate` targets are for local runs.

## Environment Variables

Terraform reads `TF_VAR_*` natively, so job parameters never appear on a command line.

### Common

| Variable             | Default                                                    | Description                                                                       |
|----------------------|------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `TF_VAR_run_id`      | (required)                                                 | Correlation id; becomes `--run-id`, the report filename, and the S3 report prefix |
| `TF_VAR_name_suffix` | `""`                                                       | Per-run suffix keeping AWS resource names unique                                  |
| `TF_VAR_image_tar`   | `hpds-etl-runner.tar.gz`                                   | Per-run container tarball name                                                    |
| `STATE_KEY`          | `tf_backend/etl-runners/hpds-etl/<name>/terraform.tfstate` | Terraform state key; set per run for concurrent builds                            |
| `IMAGE_TAR`          | `hpds-etl-runner.tar.gz`                                   | Local tarball name, matched to `TF_VAR_image_tar`                                 |
| `IMAGE_NAME`         | `hpds-etl-runner`                                          | Docker image name                                                                 |
| `SKIP_TESTS`         | `false`                                                    | Skip the JAR test suites in `make jar`                                            |
| `REPORTS_DIR`        | `<runner>/reports`                                         | Where `fetch-reports` syncs to                                                    |
| `AWS_REGION`         | from `<name>.tfvars`                                       | Region for AWS CLI calls                                                          |

### Monitor

| Variable           | Default | Description                                                    |
|--------------------|---------|----------------------------------------------------------------|
| `PIPELINE_TIMEOUT` | `7200`  | Seconds to wait for the run before exiting 124                 |
| `BOOT_TIMEOUT`     | `600`   | Seconds to wait for the instance to reach `running`            |
| `GRACE_TIMEOUT`    | `180`   | Seconds to wait for the sentinel after the instance terminates |
| `POLL_INTERVAL`    | `15`    | Seconds between checks                                         |

### Job Parameters

| Runner                           | Variables                                                                  |
|----------------------------------|----------------------------------------------------------------------------|
| `participants-migration`         | `TF_VAR_managed_inputs_uri`, `TF_VAR_data_folder_uri`, `TF_VAR_batch_size` |
| `sstr-populate-rds-participants` | `TF_VAR_study_id`, `TF_VAR_input_uri`, `TF_VAR_batch_size`                 |

### Validation Expectations

Read by the SSTR `validate.sh`; supplied per study from `studies.tsv` via the Jenkins job.

| Variable                    | Description                                                    |
|-----------------------------|----------------------------------------------------------------|
| `EXPECTED_consent_codeS`    | Comma-separated `CONSENT` values the study must produce        |
| `EXPECTED_MIN_PARTICIPANTS` | Floor on distinct participants; catches a truncated input file |

---

## Creating a New Runner

1. Copy an existing runner directory:

   ```bash
   cp -r sstr-populate-rds-participants <new-job>
   ```

2. Rename `terraform/sstr-populate-rds-participants.tfvars` and `.backend.tfvars` to match the new
   directory name. `common.mk` derives both paths from `NAME`.

3. Set `NAME` in the new `Makefile` to the directory name.

4. In `terraform/main.tf`, set `module_name` and `job_name` to the job's `name()`, and map
   `job_params` to the job's `expectations()` inputs. Keys use underscores; `run-job.sh` converts
   them to `--kebab-case` flags.

5. Replace the per-run variables in `terraform/variables.tf` with the job's parameters.

6. Rewrite `preflight.sh` and `validate.sh` for the job's inputs and metrics. Assert invariants
   rather than expected-looking numbers: check how the repository upserts before asserting a count
   is equal to anything, since `ON CONFLICT DO NOTHING` returns only newly inserted rows.

7. Add a stage to the matching orchestrator — [`/Jenkinsfile`](../Jenkinsfile) for
   `JobType.MIGRATION`, [`/Jenkinsfile.permanent`](../Jenkinsfile.permanent) for
   `JobType.PERMANENT`.

8. Enable the job in [`application.yml`](../src/main/resources/application.yml) under `etl.jobs`.
   Jobs are opt-in; without the flag `JobRegistry` never sees it.
