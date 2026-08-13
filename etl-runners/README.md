# etl-runners

One directory per job that runs on an ephemeral EC2 runner. Each holds the job's Jenkinsfile,
its Terraform module call, and its pre-flight and post-run validation.

See **[docs/JENKINS.md](../docs/JENKINS.md)** for the architecture, the exit-code contract, the
validation layers, AWS prerequisites, and the operations runbook.

```
Dockerfile      one image for every job — the JAR selects its job at runtime
run-job.sh      container entrypoint: ETL_PARAM_* ▸ --flags ▸ java -jar ▸ exit code
common.mk       shared build/deploy/monitor/validate targets, included by each Makefile
common/
├─ lib.sh                 check/soft/fail/warn/summary helpers (0 clean, 10 warn, 1 fail)
├─ monitor-runner.sh      polls for the status.json sentinel; exits with the job's exit code
└─ validate-report.sh     assertions true of every JobResult report

participants-migration/            TEMPORARY — delete with the migration
sstr-populate-rds-participants/    PERMANENT
```

## Adding a runner for a new job

1. `cp -r sstr-populate-rds-participants <new-job>` (it is the simpler of the two).
2. Rename `terraform/sstr-populate-rds-participants.tfvars` and `.backend.tfvars` to match the
   new directory name — `common.mk` derives both paths from `NAME`.
3. In the new `Makefile`, set `NAME` to the directory name.
4. In `terraform/main.tf`, set `module_name` and `job_name` to the job's `name()`, and map
   `job_params` to the job's `expectations()` inputs. Keys use underscores; `run-job.sh`
   converts them to `--kebab-case` flags.
5. Replace `terraform/variables.tf`'s per-run variables with that job's parameters.
6. Rewrite `preflight.sh` and `validate.sh` for that job's inputs and metrics. Assert
   invariants, not plausible-looking numbers — check how the repository upserts before
   asserting a count is equal to anything (`ON CONFLICT DO NOTHING` returns only new rows).
7. Add a stage to the right orchestrator: `/Jenkinsfile` for `JobType.MIGRATION`,
   `/Jenkinsfile.permanent` for `JobType.PERMANENT`.
