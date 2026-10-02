# Module: etl-runner

Provisions a **self-terminating EC2 instance that runs exactly one hpds-etl job** and
publishes its `ExitCode` plus JSON reports to S3.

The instance:

- pulls the `hpds-etl-runner` Docker image tarball from S3, loads it, and runs it
- for jobs that use the participant database, fetches its credentials from the temporary Secrets
  Manager secret (`db_secret_id`) with its instance profile and passes them to the container as
  `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` through a `600`-mode `--env-file` (never `-e`, never
  Terraform state). Jobs that never touch the database pass `db_secret_id = ""` and skip this step
  entirely, so they run whether or not the database is up
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
| `job_name` / `job_params` / `db_secret_id` inputs                      | Specialised to the hpds-etl JAR contract (`--job=<name> --run-id=<id> --key=value`) rather than a bare `docker run`.                                                |

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
  root_volume_size       = var.root_volume_size

  job_name     = "sstr-populate-rds-participants"
  run_id       = var.run_id
  db_secret_id = var.db_secret_id                 # "" for jobs that touch no database

  job_params = {
    input      = var.input_uri
    study_id   = var.study_id     # underscores here -> --study-id on the CLI
    batch_size = var.batch_size
  }

  tags = var.tags
}
```

The shared values (`aws_region`, `stack_s3_bucket`, `vpc_id`, `subnet_id`,
`vpc_security_group_ids`, `iam_role_name`, `db_secret_id`) come from
`etl-runners/environments/<ENV>.tfvars`.

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
| `iam_role_name`                              | Pre-existing role attached as instance profile                              | `string`       | `"bdc-etl-jenkins-role"`               | no       |
| `db_secret_id`                               | Participant DB secret; blank skips the credential fetch                     | `string`       | `""`                                   | no       |
| `job_params`                                 | Job parameters, keys with underscores → `--kebab-case` flags                | `map(string)`  | `{}`                                   | no       |
| `name_suffix`                                | Per-run suffix making resource names unique                                 | `string`       | `""`                                   | no       |
| `ami_owner_id`                               | AMI owner account id                                                        | `string`       | `"amazon"`                             | no       |
| `ami_name_pattern`                           | Glob selecting the most recent matching AMI                                 | `string`       | `"al2023-ami-2023.*-x86_64"`           | no       |
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

The module attaches, but does not create, `var.iam_role_name` (`bdc-etl-jenkins-role`). It is the
only principal the job runs as, so it needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::<stack-bucket>/etl-runner/container/*" },
    { "Effect": "Allow",
      "Action": ["s3:PutObject"],
      "Resource": [
        "arn:aws:s3:::<stack-bucket>/etl-runner/logs/*",
        "arn:aws:s3:::<stack-bucket>/etl-runner/reports/*"
      ] },
    { "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<data-bucket>", "arn:aws:s3:::<data-bucket>/*"] }
  ]
}
```

plus `AmazonSSMManagedInstanceCore` for Session Manager, and `sts:AssumeRole` on the NHLBI
exchange role for `generate-identity-consent-mapping`.

Secrets Manager access is **not** part of the standing policy: the `participant-db` stack attaches
an inline policy (`GetSecretValue` / `DescribeSecret` / `PutSecretValue` on its secret only) when
the database starts and removes it when the database is destroyed. See
[docs/PARTICIPANT_DB.md](../../docs/PARTICIPANT_DB.md).

The Jenkins agent additionally needs `ec2:DescribeInstances`, `ssm:SendCommand`,
`ssm:GetCommandInvocation`, `ssm:DescribeInstanceInformation`, and read/write on the stack
bucket, on top of the usual Terraform/EC2/IAM permissions to create and destroy the runner.

## Secret Format

`db_secret_id` must resolve to JSON with either a ready-made JDBC URL or discrete fields.
`participant-db-start` writes the second form once Postgres has restored and is accepting
connections:

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
  `4`; a failure resolving the secret reports `5`.
- **Database not running.** The secret exists only between `participant-db-start` and
  `participant-db-stop`. If it cannot be read, the bootstrap logs that the participant database is
  probably not running and exits `5`. The Jenkinsfiles of DB-backed runners check this on the agent
  first (`etl-runners/common/require-db.sh`), so the usual symptom is a fast, clear failure before
  anything is provisioned.
- **`user_data` is not secret-bearing.** It contains the secret's id, never its value.
- **Terraform state** holds no credentials, only instance metadata.
- **Subnet lookup.** With `subnet_id` blank, every run picks the lowest-id subnet of `vpc_id`
  (sorted, so deterministic). Pin `subnet_id` once the right subnet is confirmed.
- **Cleanup is the caller's responsibility:** `make clean` or `terraform destroy` in
  `post { always }`. The instance self-terminates regardless, so a leaked state file bills
  nothing.

## References

- [terraform-aws-modules/ec2-instance](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html)
