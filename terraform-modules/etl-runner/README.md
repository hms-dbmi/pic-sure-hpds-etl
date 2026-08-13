# Module: etl-runner

Provisions a **self-terminating EC2 instance that runs exactly one hpds-etl job** and
publishes its `ExitCode` plus JSON reports to S3.

The instance:

- pulls the `hpds-etl-runner` Docker image tarball from S3, loads it, and runs it
- fetches RDS credentials from Secrets Manager with its instance profile and passes them
  to the container through a `600`-mode `--env-file` (never `-e`, never Terraform state)
- captures the container's exit code, syncs `/reports` and the full log to S3, and uploads
  `status.json` **last** as the completion sentinel
- shuts itself down immediately afterwards (`instance_initiated_shutdown_behavior = terminate`)
- uses IMDSv2 (token required, hop limit 2 so the container can still assume the instance
  role), an encrypted root volume, and no SSH key — access is SSM Session Manager only

Wraps [`terraform-aws-modules/ec2-instance`](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest) v6.0.1.

## Relationship to bdc-etl-curation

This is a **vendored, Java-specialised copy** of `terraform-modules/etl-runner` from
[`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation). Same shape,
same conventions (`jenkins-s3-role`, `s3://<stack>/etl-runner/container|logs/`,
per-pipeline `terraform/` dir driven by a `Makefile`), with four deliberate differences:

| Difference | Why |
|---|---|
| `status.json` sentinel carrying the real exit code | The Python pipelines are monitored by grepping the log for phrases like `All studies processed`. The JAR already exits with a precise `ExitCode` (0/2/3/4/5), so the runner records it and Jenkins branches on it instead of pattern-matching prose. |
| Reports synced to `s3://<stack>/etl-runner/reports/<module>/<run_id>/` | Every hpds-etl run writes a machine-readable `JobResult` JSON; Jenkins archives and asserts on it. |
| `name_suffix` | The upstream module hard-codes the instance-profile name, so two concurrent runs of one pipeline collide. The SSTR sweep runs one instance per study, so names must be per-run. |
| `job_name` / `job_params` / `rds_secret_id` inputs | This copy is specialised to the hpds-etl JAR contract (`--job=<name> --run-id=<id> --key=value`) rather than a bare `docker run`. |

Improvements worth porting back upstream are the sentinel and `name_suffix`.

## Usage

```hcl
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"

  aws_region       = var.aws_region
  module_name      = "sstr-populate-rds-participants"
  name_suffix      = var.name_suffix        # Jenkins BUILD_NUMBER
  stack_s3_bucket  = var.stack_s3_bucket
  ami_owner_id     = var.ami_owner_id
  ami_name_pattern = var.ami_name_pattern
  instance_type    = var.instance_type
  subnet_id        = var.subnet_id
  root_volume_size = var.root_volume_size

  job_name      = "sstr-populate-rds-participants"
  run_id        = var.run_id
  rds_secret_id = var.rds_secret_id

  job_params = {
    input      = var.input_uri
    study_id   = var.study_id     # underscores here -> --study-id on the CLI
    batch_size = var.batch_size
  }

  tags = var.tags
}
```

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| `aws_region` | AWS region | `string` | — | yes |
| `module_name` | Runner name; used in resource names, tags, and S3 paths | `string` | — | yes |
| `stack_s3_bucket` | Bucket holding the image tarball, logs, and reports | `string` | — | yes |
| `ami_owner_id` | AMI owner account id (or `aws-marketplace`) | `string` | — | yes |
| `ami_name_pattern` | Glob selecting the most recent matching AMI | `string` | — | yes |
| `subnet_id` | Subnet to launch in | `string` | — | yes |
| `job_name` | Passed to the JAR as `--job` | `string` | — | yes |
| `run_id` | Passed as `--run-id`; appears in the report filename | `string` | — | yes |
| `rds_secret_id` | Secrets Manager id holding the RDS credentials | `string` | — | yes |
| `job_params` | Job parameters, keys with underscores → `--kebab-case` flags | `map(string)` | `{}` | no |
| `name_suffix` | Per-run suffix making resource names unique | `string` | `""` | no |
| `instance_type` | EC2 instance type | `string` | `"m5.large"` | no |
| `iam_role_name` | Pre-existing role attached as instance profile | `string` | `"jenkins-s3-role"` | no |
| `image_name` | Docker image name loaded from the tarball | `string` | `"hpds-etl-runner"` | no |
| `image_tar` | Tarball filename under `etl-runner/container/` | `string` | `"hpds-etl-runner.tar.gz"` | no |
| `java_opts` | `JAVA_OPTS` for the container JVM | `string` | `"-XX:MaxRAMPercentage=75"` | no |
| `log_level` | `LOG_LEVEL` for the hpds loggers | `string` | `"INFO"` | no |
| `reports_s3_prefix` | Override the report prefix | `string` | `etl-runner/reports/<module>/<run_id>` | no |
| `rds_secret_arn` | Secret ARN; only for `manage_secret_access` | `string` | `""` | no |
| `manage_secret_access` | Attach an inline `GetSecretValue` policy to `iam_role_name` | `bool` | `false` | no |
| `root_volume_size` | Root EBS size in GiB (`null` = AMI default) | `number` | `null` | no |
| `root_volume_type` / `_iops` / `_throughput` | Root EBS tuning | | `gp3` / `3000` / `125` | no |
| `user_data_template_vars` | Extra vars merged into the built-in template | `map(string)` | `{}` | no |
| `user_data_content` | Fully rendered `user_data`, overriding the template | `string` | `null` | no |
| `tags` | Additional tags on all resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| `instance_id` | EC2 instance id — what the monitor polls |
| `status_s3_uri` | Completion sentinel (`status.json`) |
| `reports_s3_uri` | Prefix the reports/CSVs are synced to |
| `log_s3_uri` | Full runner log |
| `run_id` | Correlation id passed to the job |
| `instance_arn`, `instance_state`, `availability_zone`, `private_ip`, `private_dns`, `primary_network_interface_id` | Standard instance attributes |

## IAM

The module attaches, but does not create, `var.iam_role_name`. That role needs:

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
      "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<input-bucket>", "arn:aws:s3:::<input-bucket>/*"] },
    { "Effect": "Allow",
      "Action": ["secretsmanager:GetSecretValue"],
      "Resource": "<rds-secret-arn>" }
  ]
}
```

plus `AmazonSSMManagedInstanceCore` for Session Manager. Set `manage_secret_access = true`
to have this module attach the Secrets Manager statement itself — off by default because
`jenkins-s3-role` is shared with other pipelines and is normally managed centrally.

The Jenkins agent additionally needs `ec2:DescribeInstances`, `ssm:SendCommand`,
`ssm:GetCommandInvocation`, `ssm:DescribeInstanceInformation`, and read/write on the stack
bucket, on top of the usual Terraform/EC2/IAM permissions to create and destroy the runner.

## Secret format

`rds_secret_id` must resolve to JSON with either a ready-made JDBC URL or discrete fields:

```json
{ "url": "jdbc:postgresql://hpds.abc123.us-east-1.rds.amazonaws.com:5432/hpds",
  "username": "hpds_etl", "password": "…" }
```

```json
{ "host": "hpds.abc123.us-east-1.rds.amazonaws.com", "port": 5432,
  "dbname": "hpds", "username": "hpds_etl", "password": "…" }
```

The second form is what an RDS-managed secret produces, so a rotated RDS secret works
unchanged. `engine`/`dbInstanceIdentifier` keys are ignored.

## Notes

- **Exit codes** are the whole contract: `0` success, `2` validation, `3` data,
  `4` infrastructure (retryable), `5` config, `1` unknown. Bootstrap failures before the
  container starts report `4`; a failure resolving the secret reports `5`.
- **`user_data` is not secret-bearing.** It contains the secret's *id*, never its value.
- **Terraform state** holds no credentials, only instance metadata.
- **Cleanup** is the caller's job: `make clean` / `terraform destroy` in `post { always }`.
  The instance self-terminates regardless, so a leaked state file costs nothing running.

## References

- [terraform-aws-modules/ec2-instance](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html)
