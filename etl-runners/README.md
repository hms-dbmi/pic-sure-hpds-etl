# ETL Runners

One directory per hpds-etl job that runs on an ephemeral EC2 runner, plus `participant-db/`, the
Postgres server those jobs share for the length of a pipeline run. Each runner directory holds the
job's Jenkinsfile, its Terraform module call, and its pre-flight and post-run validation.

Architecture, exit-code contract, AWS prerequisites, and the operations runbook are in
[`docs/JENKINS.md`](../docs/JENKINS.md).

## Table of Contents

- [Structure](#structure)
- [Shared Components](#shared-components)
- [Runner Contents](#runner-contents)
- [Make Targets](#make-targets)
- [Environment Variables](#environment-variables)
- [Environments](#environments)
- [Creating a New Runner](#creating-a-new-runner)

---

## Structure

```
etl-runners/
├── Dockerfile                          One image for every job; the JAR selects the job at runtime
├── run-job.sh                          Container entrypoint
├── common.mk                           Shared build/deploy/monitor targets
├── common/                             Shared shell libraries
├── environments/                       Per-environment tfvars (development.tfvars)
├── integration-tests/                  SHARED: CodeBuild project for the *IT suites (docs/JENKINS.md)
├── participant-db/                     SHARED: participant DB start/stop (docs/PARTICIPANT_DB.md)
├── participants-migration/             TEMPORARY (JobType.MIGRATION)
├── split-allconcepts/                  TEMPORARY (JobType.MIGRATION)
├── sstr-populate-rds-participants/     PERMANENT (JobType.PERMANENT)
├── generate-global-all-concepts/       PERMANENT (JobType.PERMANENT)
├── create-vcf-indexes/                 PERMANENT (JobType.PERMANENT)
├── merge-allconcepts/                  PERMANENT (JobType.PERMANENT), no DB
├── all-concepts-data-generator/        PERMANENT (JobType.PERMANENT), per study from /Jenkinsfile
└── generate-identity-consent-mapping/  PERMANENT (JobType.PERMANENT), standalone, no DB
```

`participant-db/` is not a runner: it has `Jenkinsfile.start` / `Jenkinsfile.stop` (Jenkins jobs
`participant-db-start` / `participant-db-stop`), one Terraform state per environment rather than
per run, and no JAR. See [`docs/PARTICIPANT_DB.md`](../docs/PARTICIPANT_DB.md).

## Shared Components

| File                        | Purpose                                                                                                                                 |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `Dockerfile`                | Runtime image: Amazon Corretto plus `target/hpds-etl.jar`. Built from the repository root.                                              |
| `run-job.sh`                | Container entrypoint. Converts `ETL_PARAM_<key>` environment variables to `--kebab-case` flags, runs the JAR, exits with its exit code. |
| `common.mk`                 | Build, deploy, monitor, and teardown targets, included by each runner's `Makefile`.                                                     |
| `common/lib.sh`             | Assertion helpers: `check`, `soft`, `fail`, `warn`, `note`, `summary`.                                                                  |
| `common/monitor-runner.sh`  | Polls EC2 state and the `status.json` sentinel; exits with the job's own exit code.                                                     |
| `common/require-db.sh`      | Run on the agent before provisioning a DB-backed runner: fails in seconds if the participant DB secret has no current value (DB down).   |
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

### Database access

Six runners read or write the participant DB: `sstr-populate-rds-participants`,
`participants-migration`, `split-allconcepts`, `generate-global-all-concepts`,
`create-vcf-indexes`, `all-concepts-data-generator`. Their module call passes
`db_secret_id = var.db_secret_id`, and their `Provision, run and monitor` stage calls
`common/require-db.sh` first. `merge-allconcepts` and `generate-identity-consent-mapping` pass
`db_secret_id = ""`: the runner skips the credential fetch and the job runs whether or not the
database is up.

### AWS identity

Every runner, the participant DB, and the Jenkins agent run as `bdc-etl-jenkins-role`
(`iam_role_name` in the environment file), in the same account as the data. No cross-account role
is assumed. The one exception is `generate-identity-consent-mapping`, which assumes the NHLBI
exchange role in-process (`ROLE_ARN` → `--role-arn`) for its input reads; its Init stage also
writes an agent-side `nhlbi-exchange` profile for the pre-flight.

## Make Targets

Defined in `common.mk` unless noted.

| Target          | Description                                                               |
|-----------------|---------------------------------------------------------------------------|
| `help`          | List targets for this runner (default goal)                               |
| `jar`           | `./mvnw clean package` at the repository root (`SKIP_TESTS=true` to skip) |
| `context`       | Tar the image build context (JAR, `Dockerfile`, `run-job.sh`) to `$(CONTEXT_TAR)` |
| `context-upload`| Upload the tarball to `s3://<stack>/etl-runner/container/`                |
| `package`       | `context` + `context-upload`; the instance runs `podman build` from it    |
| `init`          | `terraform init -reconfigure` with the backend config and `STATE_KEY`     |
| `validate-tf`   | `terraform validate`                                                      |
| `plan`          | `terraform plan`                                                          |
| `apply`         | `terraform apply --auto-approve`; creates the ephemeral instance          |
| `monitor`       | Wait for the run to finish                                                |
| `run`           | `apply` + `monitor`                                                       |
| `fetch-reports` | `aws s3 sync` the run's reports into `$(REPORTS_DIR)`                     |
| `output`        | `terraform output`                                                        |
| `destroy`       | `terraform destroy --auto-approve`                                        |
| `clean`         | `destroy` plus removal of the local context tarball                       |
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
| `TF_VAR_context_tar` | `hpds-etl-context.tar.gz`                                  | Per-run image build-context tarball name                                          |
| `STATE_KEY`          | `tf_backend/etl-runners/hpds-etl/<name>/terraform.tfstate` | Terraform state key; set per run for concurrent builds                            |
| `CONTEXT_TAR`        | `hpds-etl-context.tar.gz`                                  | Local tarball name, matched to `TF_VAR_context_tar`                               |
| `ENV`                | `development`                                              | Target environment; selects `environments/<ENV>.tfvars`                           |
| `SKIP_TESTS`         | `false`                                                    | Skip the JAR test suites in `make jar`                                            |
| `REPORTS_DIR`        | `<runner>/reports`                                         | Where `fetch-reports` syncs to                                                    |
| `AWS_REGION`         | from environment tfvars                                    | Region for AWS CLI calls                                                          |

### Monitor

| Variable           | Default | Description                                                    |
|--------------------|---------|----------------------------------------------------------------|
| `PIPELINE_TIMEOUT` | `7200`  | Seconds to wait for the run before exiting 124                 |
| `BOOT_TIMEOUT`     | `600`   | Seconds to wait for the instance to reach `running`            |
| `GRACE_TIMEOUT`    | `180`   | Seconds to wait for the sentinel after the instance terminates |
| `POLL_INTERVAL`    | `15`    | Seconds between checks                                         |

### Job Parameters

| Runner                              | Variables                                                                  |
|-------------------------------------|----------------------------------------------------------------------------|
| `participants-migration`            | `TF_VAR_managed_inputs_uri`, `TF_VAR_data_folder_uri`, `TF_VAR_batch_size` |
| `split-allconcepts`                 | `TF_VAR_study_id`, `TF_VAR_abbreviation`, `TF_VAR_input_uri`, `TF_VAR_mapping_uri`, `TF_VAR_output_uri` |
| `sstr-populate-rds-participants`    | `TF_VAR_study_id`, `TF_VAR_input_uri`, `TF_VAR_batch_size`                 |
| `merge-allconcepts`                 | `TF_VAR_input_uri`, `TF_VAR_study_ids`                                     |
| `all-concepts-data-generator`       | `TF_VAR_study_id`, `TF_VAR_data_dir`, `TF_VAR_mapping_uri`, `TF_VAR_output_uri`, `TF_VAR_skip_analysis` |
| `generate-global-all-concepts`      | `TF_VAR_output_uri`, `TF_VAR_managed_inputs_uri`, `TF_VAR_allow_empty`     |
| `create-vcf-indexes`                | `TF_VAR_output_uri`, `TF_VAR_managed_inputs_uri`, `TF_VAR_include_processed` |
| `generate-identity-consent-mapping` | `TF_VAR_base_uri`, `TF_VAR_dataset_prefix`, `TF_VAR_input_role_arn`, `TF_VAR_output_uri`, `TF_VAR_per_study` |

Every runner also takes `TF_VAR_instance_type`, from its Jenkins `INSTANCE_TYPE` parameter.

> **Never set a `TF_VAR`-driven variable in a runner's `.tfvars`.** Terraform gives `-var-file`
> values precedence over `TF_VAR_*` environment variables, so a value in `<name>.tfvars` silently
> overrides the Jenkins parameter. This is why `instance_type` lives only as a default in
> `variables.tf`: until it was removed from the `.tfvars` files, no runner's `INSTANCE_TYPE`
> parameter ever took effect.

### Validation Expectations

Read by the SSTR `validate.sh`; supplied as parameters of the `sstr-populate-rds-participants`
Jenkins job (set them on a manual single-study run; the orchestrator does not pass them).

| Variable                    | Description                                                    |
|-----------------------------|----------------------------------------------------------------|
| `EXPECTED_CONSENT_CODES`    | Comma-separated `CONSENT` values the study must produce        |
| `EXPECTED_MIN_PARTICIPANTS` | Floor on distinct participants; catches a truncated input file |

---

## Environments

Infrastructure settings shared by every runner and the participant DB (account, region, VPC,
subnet, security groups, instance role, DB secret name) live in `environments/<ENV>.tfvars`. Each
runner's own `<name>.tfvars` holds only runner-specific settings (volume size, tags). `common.mk`
loads both files: the environment file first, then the runner file.

The default environment is `development` (`ENV ?= development` in `common.mk`). Every
Jenkinsfile exposes `ENV` as a build parameter and sets it in the pipeline's environment
block so `make` picks it up automatically; the orchestrators pass it to every job they trigger.

### Current environments

| Name          | File                               | Account        |
|---------------|------------------------------------|----------------|
| `development` | `environments/development.tfvars`  | `515157839325` |

### Adding a new environment

1. Copy `environments/development.tfvars` to `environments/<name>.tfvars`.

2. Update the values for the new environment:

   | Setting                  | What to change                                                       |
   |--------------------------|----------------------------------------------------------------------|
   | `aws_account_id`         | The target account; every provider refuses any other                 |
   | `aws_region`             | Region (if different)                                                |
   | `stack_s3_bucket`        | Bucket for container images, logs, reports, and Terraform state     |
   | `iam_role_name`          | Instance profile role (and the agent's role) in that account         |
   | `vpc_id` / `subnet_id`   | Target VPC; blank `subnet_id` uses its lowest-id subnet — pin it     |
   | `vpc_security_group_ids` | Runner security group(s); the only sources allowed to reach the DB   |
   | `db_secret_id`           | Name for the temporary participant DB secret (unique per environment) |
   | `ami_owner_id` / `ami_name_pattern` | AMI: the SRCE RHEL9 golden image (bootstraps assume podman and RHEL9 module streams) |

   Also check the bucket in each `terraform/*.backend.tfvars` and the backup bucket/prefix in
   `participant-db/terraform/participant-db.tfvars` (dumps go under `<prefix>/<env>/backups/`).

3. Add the new name to the `choices` list of the `ENV` parameter in every Jenkinsfile:

   - `Jenkinsfile` and `Jenkinsfile.migration` (orchestrators)
   - `etl-runners/*/Jenkinsfile` (all eight runners)
   - `etl-runners/participant-db/Jenkinsfile.start` and `Jenkinsfile.stop`

   ```groovy
   choice(name: 'ENV', choices: ['development', '<name>'],
          description: 'Target environment.')
   ```

4. Verify locally before the first Jenkins run:

   ```bash
   make -C etl-runners/participants-migration init plan ENV=<name>
   ```

### Switching environments

From Jenkins, select the environment in the build parameters dropdown. From the command line:

```bash
make -C etl-runners/participants-migration plan ENV=<name>
```

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
   them to `--kebab-case` flags. If the job never touches the database, set `db_secret_id = ""`
   and remove the `require-db.sh` check from the Jenkinsfile's provision stage.

5. Replace the per-run variables in `terraform/variables.tf` with the job's parameters. Keep
   anything Jenkins sets through `TF_VAR_*` out of `<name>.tfvars` (see the precedence note above).

6. Rewrite `preflight.sh` and `validate.sh` for the job's inputs and metrics. Assert invariants
   rather than expected-looking numbers: check how the repository upserts before asserting a count
   is equal to anything, since `ON CONFLICT DO NOTHING` returns only newly inserted rows.

7. Add a stage to the matching orchestrator — [`/Jenkinsfile`](../Jenkinsfile) for
   `JobType.PERMANENT`, [`/Jenkinsfile.migration`](../Jenkinsfile.migration) for
   `JobType.MIGRATION` — after `Start participant DB` if the job uses the database. Create the
   Jenkins job inside the `hpds-etl` folder so the orchestrator's relative job name resolves.

8. Enable the job in [`application.yml`](../src/main/resources/application.yml) under `etl.jobs`.
   Jobs are opt-in; without the flag `JobRegistry` never sees it.
