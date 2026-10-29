# Module: etl-runner

Provisions a **self-terminating EC2 instance that runs exactly one hpds-etl job** and
publishes its `ExitCode` plus JSON reports to S3.

The instance:

- boots the SRCE RHEL9 golden image (the pheno ETL environment's AMI) with podman enabled
- pulls the `hpds-etl-runner` image tarball (built and saved on the Jenkins agent) from S3, and
  `podman load`s and `podman run`s it
- for jobs that use the participant database (`uses_participant_db = true`), reads its
  credentials at boot from an SSM SecureString parameter (see
  [Participant DB credentials](#participant-db-credentials)) and passes them to the container as
  `DB_URL` (with `sslmode=require`) / `DB_USERNAME` / `DB_PASSWORD` through a `600`-mode
  `--env-file` (never `-e`). Jobs that never touch the database leave `uses_participant_db` at
  `false` and skip this step entirely, so they run whether or not the database is up
- captures the container's exit code, syncs `/reports` and the full log to S3, and uploads
  `status.json` **last** as the completion sentinel
- shuts itself down immediately afterwards (`instance_initiated_shutdown_behavior = terminate`)
- uses IMDSv2 (token required, hop limit 2 so the AWS SDK inside the container reaches the
  instance-role credentials it uses for **all** S3 I/O), an encrypted root volume, and no SSH
  key — access is SSM Session Manager only

With `pool_prefix` set it instead runs as a long-lived pool worker that takes many runs, one at a
time, from an S3 mailbox — see [Worker mode](#worker-mode). Single mode is the default.

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
  environment         = var.environment   # -> /hpds-etl/<environment>/participant-db/app-credentials

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
`etl-runners/environments/<ENV>.tfvars`. `environment` is set by `etl-runners/common.mk` as
`TF_VAR_environment=$(ENV)`. No credential passes through Terraform: the instance reads it from SSM.

## Inputs

| Name                                         | Description                                                                 | Type           | Default                                | Required |
|----------------------------------------------|-----------------------------------------------------------------------------|----------------|----------------------------------------|:--------:|
| `aws_region`                                 | AWS region                                                                  | `string`       | —                                      | yes      |
| `module_name`                                | Runner name; used in resource names, tags, and S3 paths                     | `string`       | —                                      | yes      |
| `stack_s3_bucket`                            | Bucket holding the image tarball, logs, and reports                         | `string`       | —                                      | yes      |
| `job_name`                                   | Passed to the JAR as `--job`                                                | `string`       | —                                      | yes      |
| `run_id`                                     | Passed as `--run-id`; appears in the report filename                        | `string`       | —                                      | yes      |
| `vpc_id`                                     | VPC; its lowest-id subnet is used when `subnet_id` is blank                 | `string`       | `""`                                   | one of   |
| `subnet_id`                                  | Subnet to launch in (preferred); blank = looked up from `vpc_id`            | `string`       | `""`                                   | one of   |
| `vpc_security_group_ids`                     | Security groups (blank = VPC default)                                       | `list(string)` | `[]`                                   | no       |
| `iam_role_name`                              | Pre-existing role the instance runs as                                      | `string`       | `"bdc-etl-jenkins-role"`               | no       |
| `iam_instance_profile_name`                  | Pre-existing instance profile to attach; blank creates one per run          | `string`       | `""`                                   | no       |
| `uses_participant_db`                        | Job needs the participant DB; `false` skips the credential step             | `bool`         | `false`                                | no       |
| `environment`                                | ETL environment; names the default credentials parameter                    | `string`       | `""`                                   | with DB  |
| `participant_db_credentials_parameter`       | SSM parameter with the DB credentials; blank = derived from `environment`   | `string`       | `/hpds-etl/<environment>/participant-db/app-credentials` | no |
| `job_params`                                 | Job parameters, keys with underscores → `--kebab-case` flags; keys `[A-Za-z_][A-Za-z0-9_-]*`, values single-line | `map(string)` | `{}`              | no       |
| `name_suffix`                                | Per-run suffix making resource names unique                                 | `string`       | `""`                                   | no       |
| `ami_owner_id`                               | AMI owner account id                                                        | `string`       | `"amazon"`                             | no       |
| `ami_name_pattern`                           | Glob selecting the most recent matching AMI                                 | `string`       | `"srce-rhel9-golden*"`           | no       |
| `instance_type`                              | EC2 instance type                                                           | `string`       | `"m5.large"`                           | no       |
| `image_tar`                                  | Tarball filename under `etl-runner/container/`; the image name is this minus `.tar[.gz]` | `string` | `"hpds-etl-runner.tar.gz"`     | no       |
| `java_opts`                                  | `JAVA_OPTS` for the container JVM                                           | `string`       | `"-XX:MaxRAMPercentage=75"`            | no       |
| `log_level`                                  | `LOG_LEVEL` for the hpds loggers                                            | `string`       | `"INFO"`                               | no       |
| `reports_s3_prefix`                          | Override the report prefix                                                  | `string`       | `etl-runner/reports/<module>/<run_id>` | no       |
| `root_volume_size`                           | Root EBS size in GiB (`null` = AMI default)                                 | `number`       | `null`                                 | no       |
| `root_volume_type` / `_iops` / `_throughput` | Root EBS tuning                                                             |                | `gp3` / `3000` / `125`                 | no       |
| `pool_prefix`                                | Worker mode: mailbox root key prefix; blank = single mode                   | `string`       | `""`                                   | no       |
| `pool_idle_timeout`                          | Worker mode: seconds idle before a worker terminates (60–86400)             | `number`       | `1800`                                 | no       |
| `user_data_template_vars`                    | Extra vars merged into the built-in template (override the module's own)    | `map(string)`  | `{}`                                   | no       |
| `user_data_content`                          | Fully rendered `user_data`, overriding the template (still gzipped)         | `string`       | `null`                                 | no       |
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

**Participant DB credentials (runners with `uses_participant_db = true`).** The instance role
reads the credentials parameter at boot, so it additionally needs:

```json
{
  "Effect": "Allow",
  "Action": "ssm:GetParameter",
  "Resource": "arn:aws:ssm:<region>:<account>:parameter/hpds-etl/<ENV>/participant-db/app-credentials"
},
{
  "Effect": "Allow",
  "Action": "kms:Decrypt",
  "Resource": "<arn of the parameter's KMS key>",
  "Condition": { "StringEquals": { "kms:ViaService": "ssm.<region>.amazonaws.com" } }
}
```

With the default AWS-managed key (`alias/aws/ssm`), `kms:Decrypt` through SSM is already allowed
to principals in the account, so the `ssm:GetParameter` statement is enough; a customer-managed
key needs the `kms:Decrypt` statement (and a key policy that allows it). The Jenkins server's role
needs `ssm:PutParameter` on the same ARN for `participant-db-start` to write it.

## Participant DB credentials

`participant-db-start` writes the app role's credentials, after rotating its password on every
start, to the SSM SecureString parameter **`/hpds-etl/<ENV>/participant-db/app-credentials`**
(`<ENV>` = `development`, …; override the name with `participant_db_credentials_parameter`). A
DB-backed runner reads it at boot as the instance role:

```bash
aws ssm get-parameter --with-decryption --name /hpds-etl/<ENV>/participant-db/app-credentials \
  --query Parameter.Value --output text
```

retried like the other bootstrap calls; if it is missing, unreadable, not JSON, or lacks a
non-empty `username` or `password`, the run fails in its `credentials` phase with exit `5`.
Neither user data nor Terraform state holds the credentials, only the parameter name; a runner
provisioned before a restart of the database still reads the current password when it boots.

The value is JSON with either discrete fields — what `participant-db-start` writes (plus
`engine`, `schema` and `container`, ignored here) —

```json
{ "engine": "postgres", "host": "10.0.1.23", "port": 5432, "dbname": "etl_db",
  "schema": "etl", "username": "hpds_etl", "password": "…" }
```

or a ready-made JDBC URL:

```json
{ "url": "jdbc:postgresql://10.0.1.23:5432/etl_db", "username": "hpds_etl", "password": "…" }
```

`url` (or `jdbcUrl`) wins when present; otherwise the URL is built from `host`, `port` (default
`5432`) and `dbname` (or `dbName`). `sslmode=require` is then appended (`?` or `&`, whichever the
URL needs) unless the URL already sets an `sslmode`: the participant database requires TLS.
`engine` and `schema` are ignored here (the JAR takes its schema from `DB_SCHEMA`, default `etl`).

## Worker mode

**Available, not yet used by the permanent pipeline**, whose jobs each still run on their own
instance. [`etl-runners/runner-pool`](../../etl-runners/runner-pool/) provisions a pool of
workers; any job can be dispatched to them.

With `pool_prefix` set, the instance boots once — packages, credentials, image — and then runs
tasks from its S3 mailbox, `s3://<stack_s3_bucket>/<pool_prefix>/<instance-id>/task.json`, one at
a time. [`etl-runners/common/pool-dispatch.sh`](../../etl-runners/common/pool-dispatch.sh) writes a
task:

```json
{ "runId": "<run-id>", "module": "<module>",
  "reportsPrefix": "etl-runner/reports/<module>/<run-id>", "logKey": "etl-runner/logs/<module>-<run-id>.log",
  "env": { "ETL_JOB": "<job>", "ETL_RUN_ID": "<run-id>", "ETL_PARAM_<key>": "<value>" } }
```

The worker claims a task by deleting it from the mailbox (it does not run it until the delete
succeeds, so a task never runs twice), runs the container with the boot environment minus the
pool's own `ETL_JOB`/`ETL_RUN_ID`/`ETL_PARAM_*` plus the task's, and publishes exactly what a
single run would: reports (minus `*.partial`) and the `status.json` sentinel (last) under
`reportsPrefix`, the container's output at `logKey`. So `monitor-runner.sh`, `fetch-reports` and the
validators work on a task unchanged; `monitor-runner.sh` gets the worker's instance id.

- Only `ETL_JOB`, `ETL_RUN_ID` (equal to `runId`) and `ETL_PARAM_*` keys with single-line string
  values are accepted; a task with anything else, or a malformed job or module, gets a sentinel
  with exit `5` and the reason in its log. A task without a usable `runId`, `reportsPrefix` or
  `logKey` (or not JSON) is dropped with a line in the worker log only: there is nowhere safe to
  publish a result.
- A task interrupted by a worker crash still gets a sentinel with exit `4`. A task interrupted by
  the instance being terminated (SIGTERM — e.g. `pool-retire-worker.sh` after its caller gave up)
  gets nothing, so it cannot overwrite a retry already dispatched elsewhere under the same run id;
  a caller still waiting sees the instance terminate.
- The worker logs any mailbox poll error other than "no task" (e.g. missing `s3:GetObject`).
- A worker terminates on a `stop` object in its mailbox, after `pool_idle_timeout` seconds with no
  task (the backstop if the dispatcher dies), or on any boot failure. Its own log lands at
  `etl-runner/logs/<module_name>-<run_id>-<instance-id>.log`.
- The DB credentials are read once, at boot: a pool must not outlive a `participant-db-start`
  (which rotates the password).
- One task at a time per worker: `pool-dispatch.sh` refuses to overwrite an unclaimed task, and a
  timed-out task's worker should be retired (`etl-runners/common/pool-retire-worker.sh`) rather
  than given another.

## Notes

- **Exit codes** are the contract: `0` success, `2` validation, `3` data, `4` infrastructure
  (retryable), `5` config, `1` unknown. Bootstrap failures before the container starts report
  `4`; missing or unusable credentials (phase `credentials`) report `5`.
- **Database not running.** The credentials parameter exists once `participant-db-start` has
  run. With `uses_participant_db = true` and no readable parameter, the bootstrap logs that the
  participant database is probably not running and exits `5`. The Jenkinsfiles of DB-backed
  runners check the database on the agent first (`etl-runners/common/require-db.sh`), so the
  usual symptom is a fast, clear failure before anything is provisioned.
- **What is and is not protected.** User data holds no secret (cloud-init keeps copies of it under
  `/var/lib/cloud/instance/`, and IMDS serves it to any process on the instance). The credentials
  live only in the `600`-mode env file under the `700` work directory, shredded on exit, and in
  the container's environment — root on the instance can still read them, including through
  `podman inspect` (`.Config.Env`). Keeping them out of `-e` only keeps them out of the process
  table and command echoes.
- **User data size.** EC2 caps user data at 16 KB; the rendered script is passed gzipped
  (`base64gzip`, which cloud-init decompresses), about 9.5 KB base64 in the largest variant
  (worker mode with the database) against roughly 19 KB raw (25 KB base64) uncompressed.
- **Subnet lookup.** With `subnet_id` blank, every run picks the lowest-id subnet of `vpc_id`
  (sorted, so deterministic, but arbitrary: not necessarily private or best-routed). Prefer
  pinning `subnet_id`. Planning fails if both are blank, or if `vpc_id` has no subnets.
- **Cleanup is the caller's responsibility:** `make clean` or `terraform destroy` in
  `post { always }`. The instance self-terminates regardless, so a leaked state file bills
  nothing.

## References

- [terraform-aws-modules/ec2-instance](https://registry.terraform.io/modules/terraform-aws-modules/ec2-instance/aws/latest)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html)
