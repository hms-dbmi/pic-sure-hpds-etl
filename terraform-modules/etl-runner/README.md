# Module: etl-runner

Provisions a **self-terminating EC2 instance that runs exactly one hpds-etl job** and
publishes its `ExitCode` plus JSON reports to S3.

The instance:

- boots the SRCE RHEL9 golden image (the pheno ETL environment's AMI) with podman enabled
- pulls the `hpds-etl-runner` image tarball (built and saved on the Jenkins agent) from S3, and
  `podman load`s and `podman run`s it
- for jobs that use the participant database (`uses_participant_db = true`), decodes its
  credentials from user data (`db_credentials_json`, base64-encoded) and passes them to the
  container as `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` through a `600`-mode `--env-file` (never
  `-e`). Jobs that never touch the database leave `uses_participant_db` at `false` and skip this
  step entirely, so they run whether or not the database is up
- captures the container's exit code, syncs `/reports` and the full log to S3, and uploads
  `status.json` **last** as the completion sentinel
- shuts itself down immediately afterwards (`instance_initiated_shutdown_behavior = terminate`)
- uses IMDSv2 (token required, hop limit 2 so the AWS SDK inside the container reaches the
  instance-role credentials it uses for **all** S3 I/O), an encrypted root volume, and no SSH
  key — access is SSM Session Manager only

Wraps [`terraform-aws-modules/ec2-instance`](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest) v6.0.1.

## Relationship to bdc-etl-curation

A vendored, Java-specialised copy of `terraform-modules/etl-runner` from
[`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation), keeping the same
shape and conventions: `s3://<stack>/etl-runner/container|logs/` and a per-pipeline `terraform/`
directory driven by a `Makefile`.

Four deliberate differences:

| Difference                                                             | Rationale                                                                                                                                                         |
|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `status.json` sentinel carrying the exit code                          | The JAR exits with a precise `ExitCode` (0/2/3/4/5), so the runner records it and Jenkins branches on it. Upstream detects completion by matching log text.         |
| Reports synced to `s3://<stack>/etl-runner/reports/<module>/<run_id>/` | Every run writes a machine-readable `JobResult` JSON that Jenkins archives and asserts on.                                                                          |
| `name_suffix`                                                          | Upstream hard-codes the instance-profile name, so two concurrent runs of one pipeline collide. The SSTR sweep can run one instance per study, requiring per-run names. |
| `job_name` / `job_params` / `uses_participant_db` inputs               | Specialised to the hpds-etl JAR contract (`--job=<name> --run-id=<id> --key=value`) rather than a bare `docker run`.                                                |

The sentinel and `name_suffix` are candidates for porting back upstream.

## Usage

```hcl
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region             = var.aws_region
  module_name            = "sstr-populate-rds-participants"
  name_suffix            = var.name_suffix        # unique per run
  stack_s3_bucket        = var.stack_s3_bucket
  ami_owner_id           = var.ami_owner_id
  ami_name_pattern       = var.ami_name_pattern
  instance_type          = var.instance_type
  vpc_id                 = var.vpc_id             # subnet looked up when subnet_id is blank
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.vpc_security_group_ids
  iam_role_name          = var.iam_role_name      # bdc-etl-jenkins-role
  iam_instance_profile_name = var.iam_instance_profile_name   # bdc-etl-jenkins-temp-profile
  root_volume_size       = var.root_volume_size

  job_name = "sstr-populate-rds-participants"
  run_id   = var.run_id

  # Omit both for jobs that touch no database.
  uses_participant_db = true
  db_credentials_json = var.db_credentials_json   # declared sensitive in the stack

  job_params = {
    input      = var.input_uri
    study_id   = var.study_id     # underscores here -> --study-id on the CLI
    batch_size = var.batch_size
  }

  tags = var.tags
}
```

The shared values (`aws_region`, `stack_s3_bucket`, `vpc_id`, `subnet_id`,
`vpc_security_group_ids`, `iam_role_name`) come from
`etl-runners/environments/<ENV>.tfvars`. `db_credentials_json` is set by `etl-runners/common.mk`'s
`plan` and `apply` as `TF_VAR_db_credentials_json`, from the file `participant-db-start` saved on
the Jenkins server (empty when there is none).

## Inputs

| Name                                         | Description                                                                 | Type           | Default                                | Required |
|----------------------------------------------|-----------------------------------------------------------------------------|----------------|----------------------------------------|:--------:|
| `aws_region`                                 | AWS region                                                                  | `string`       | —                                      | yes      |
| `module_name`                                | Runner name; used in resource names, tags, and S3 paths                     | `string`       | —                                      | yes      |
| `stack_s3_bucket`                            | Bucket holding the image tarball, logs, and reports                         | `string`       | —                                      | yes      |
| `job_name`                                   | Passed to the JAR as `--job`                                                | `string`       | —                                      | yes      |
| `run_id`                                     | Passed as `--run-id`; appears in the report filename                        | `string`       | —                                      | yes      |
| `vpc_id`                                     | VPC; its lowest-id subnet is used when `subnet_id` is blank                 | `string`       | `""`                                   | one of   |
| `subnet_id`                                  | Subnet to launch in; blank = looked up from `vpc_id`                        | `string`       | `""`                                   | one of   |
| `vpc_security_group_ids`                     | Security groups (blank = VPC default)                                       | `list(string)` | `[]`                                   | no       |
| `iam_role_name`                              | Pre-existing role the instance runs as                                      | `string`       | `"bdc-etl-jenkins-role"`               | no       |
| `iam_instance_profile_name`                  | Pre-existing instance profile to attach; blank creates one per run          | `string`       | `""`                                   | no       |
| `uses_participant_db`                        | Job needs the participant DB; `false` skips the credential step             | `bool`         | `false`                                | no       |
| `db_credentials_json`                        | Participant DB credentials JSON (sensitive); required when the above is set | `string`       | `""`                                   | no       |
| `job_params`                                 | Job parameters, keys with underscores → `--kebab-case` flags                | `map(string)`  | `{}`                                   | no       |
| `name_suffix`                                | Per-run suffix making resource names unique                                 | `string`       | `""`                                   | no       |
| `ami_owner_id`                               | AMI owner account id                                                        | `string`       | `"amazon"`                             | no       |
| `ami_name_pattern`                           | Glob selecting the most recent matching AMI                                 | `string`       | `"srce-rhel9-golden*"`           | no       |
| `instance_type`                              | EC2 instance type                                                           | `string`       | `"m5.large"`                           | no       |
| `image_name`                                 | Docker image name loaded from the tarball                                   | `string`       | `"hpds-etl-runner"`                    | no       |
| `image_tar`                                  | Tarball filename under `etl-runner/container/`                              | `string`       | `"hpds-etl-runner.tar.gz"`             | no       |
| `java_opts`                                  | `JAVA_OPTS` for the container JVM                                           | `string`       | `"-XX:MaxRAMPercentage=75"`            | no       |
| `log_level`                                  | `LOG_LEVEL` for the hpds loggers                                            | `string`       | `"INFO"`                               | no       |
| `reports_s3_prefix`                          | Override the report prefix                                                  | `string`       | `etl-runner/reports/<module>/<run_id>` | no       |
| `root_volume_size`                           | Root EBS size in GiB (`null` = AMI default)                                 | `number`       | `null`                                 | no       |
| `root_volume_type` / `_iops` / `_throughput` | Root EBS tuning                                                             |                | `gp3` / `3000` / `125`                 | no       |
| `user_data_template_vars`                    | Extra vars merged into the built-in template                                | `map(string)`  | `{}`                                   | no       |
| `user_data_content`                          | Fully rendered `user_data`, overriding the template                         | `string`       | `null`                                 | no       |
| `tags`                                       | Additional tags on all resources                                            | `map(string)`  | `{}`                                   | no       |

There is no cross-account input: the data lives in the same account as the runners, so the
container does all S3 work as the instance role. The one job that reads another account's bucket
(`generate-identity-consent-mapping`, the NHLBI exchange) assumes that role in-process via its own
`--role-arn` job parameter.

## Outputs

| Name                                                                                                               | Description                              |
|--------------------------------------------------------------------------------------------------------------------|------------------------------------------|
| `instance_id`                                                                                                      | EC2 instance id — what the monitor polls |
| `status_s3_uri`                                                                                                    | Completion sentinel (`status.json`)      |
| `reports_s3_uri`                                                                                                   | Prefix the reports/CSVs are synced to    |
| `log_s3_uri`                                                                                                       | Full runner log                          |
| `run_id`                                                                                                           | Correlation id passed to the job         |
| `instance_arn`, `instance_state`, `availability_zone`, `private_ip`, `private_dns`, `primary_network_interface_id` | Standard instance attributes             |

## IAM

The module creates no IAM resources when `iam_instance_profile_name` is set
(`bdc-etl-jenkins-temp-profile` in development): the runner attaches that existing profile, which
wraps `var.iam_role_name` (`bdc-etl-jenkins-role`). Left blank, it creates a per-run profile around
the role instead, which needs instance-profile permissions this account does not grant.

The role's minimum permissions, for the runners, the participant DB, and the Jenkins server that
launches them, are listed with a ready-to-attach policy in
[docs/JENKINS.md](../../docs/JENKINS.md#2-iam).

## Credentials Format

`db_credentials_json` must be JSON with either a ready-made JDBC URL or discrete fields.
`participant-db-start` saves the second form (plus an `instanceId`, ignored here) once Postgres
has restored and is accepting connections:

```json
{ "engine": "postgres", "host": "10.0.1.23", "port": 5432, "dbname": "etl_db",
  "schema": "etl", "username": "hpds_etl", "password": "…" }
```

```json
{ "url": "jdbc:postgresql://10.0.1.23:5432/etl_db", "username": "hpds_etl", "password": "…" }
```

`url` (or `jdbcUrl`) wins when present; otherwise the URL is built from `host`, `port` (default
`5432`) and `dbname`. `engine` and `schema` are ignored here (the JAR takes its schema from
`DB_SCHEMA`, default `etl`).

## Notes

- **Exit codes** are the contract: `0` success, `2` validation, `3` data, `4` infrastructure
  (retryable), `5` config, `1` unknown. Bootstrap failures before the container starts report
  `4`; missing or unusable credentials (phase `credentials`) report `5`.
- **Database not running.** The credentials file exists on the Jenkins server only between
  `participant-db-start` and `participant-db-stop`. With `uses_participant_db = true` and empty
  `db_credentials_json`, the bootstrap logs that the participant database is probably not running
  and exits `5`. The Jenkinsfiles of DB-backed runners check this on the agent first
  (`etl-runners/common/require-db.sh`), so the usual symptom is a fast, clear failure before
  anything is provisioned.
- **`user_data` and Terraform state carry the DB password** when `uses_participant_db = true`:
  it is in the instance's user data and in this runner's Terraform state (and older state
  versions in the versioned `tf_backend` bucket), valid for the life of that one DB instance.
  The variable is `sensitive`, so Terraform does not print it, and it is never written to the
  Jenkins console. With `uses_participant_db = false`, neither holds any credentials.
- **Subnet lookup.** With `subnet_id` blank, every run picks the lowest-id subnet of `vpc_id`
  (sorted, so deterministic). Pin `subnet_id` once the right subnet is confirmed.
- **Cleanup is the caller's responsibility:** `make clean` or `terraform destroy` in
  `post { always }`. The instance self-terminates regardless, so a leaked state file bills
  nothing.

## References

- [terraform-aws-modules/ec2-instance](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html)
