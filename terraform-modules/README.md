# terraform-modules

Shared Terraform modules for the PIC-SURE HPDS ETL pipelines. Each module is a self-contained,
reusable unit — callers reference it via a relative `source` path.

## Modules

| Module | Description |
|--------|-------------|
| [`etl-runner`](./etl-runner/README.md) | Self-terminating EC2 instance that runs one hpds-etl job and publishes its exit code and reports to S3 |

## Usage convention

Modules are called from a runner's `terraform/` directory using a relative path:

```hcl
module "etl_runner" {
  source = "../../../terraform-modules/etl-runner"
  # ...
}
```

Run `terraform init` after adding or changing a module source.

## Relationship to bdc-etl-curation

`etl-runner` is a vendored copy of the module of the same name in
[`hms-dbmi/bdc-etl-curation`](https://github.com/hms-dbmi/bdc-etl-curation), specialised for
the hpds-etl JAR contract. It is vendored rather than referenced across repositories so that
`terraform init` needs no cross-repo git credentials and so this repo's Java-specific changes
(the `status.json` exit-code sentinel, `name_suffix` for concurrent runs) do not have to land
in the Python repo first. Both are worth porting back upstream — see the module's README for
the full list of differences.

## Adding a new module

1. Create `terraform-modules/<name>/`
2. Add `main.tf`, `variables.tf`, `outputs.tf`, and `README.md`
3. Follow the [Terraform module structure](https://developer.hashicorp.com/terraform/language/modules/develop#module-structure) convention
4. Call it from the relevant runner's `terraform/` directory
