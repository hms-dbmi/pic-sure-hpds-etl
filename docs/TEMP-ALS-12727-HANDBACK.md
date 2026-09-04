# TEMPORARY working note — DELETE BEFORE MERGING `BAM-microservices`

> Short-lived handback note for ALS-12727 so the next person can pick this up with zero
> context. Everything durable lives in `docs/HANDOFF-BAM-2026-08-31.md` (2026-09-04
> section) and the commit messages. Once the job has one green run and the staging
> decision is made, fold anything still useful into the handoff doc and delete this file.

## What this is

**ALS-12727** asks for a consent mapping file: `Person.Identity, study_id, consent_code`
(Person.Identity = the dbGaP subject id). The DMC harmonization drops in
`s3://nih-nhlbi-bdc-harmdata-exchange/BDC-DMC-Harmonization-Examples-YYYYMMDD/` are
partitioned by consent group, and each consent-group folder name carries the study and
consent (e.g. `nih-nhlbi-topmed-parent-aric-phs000280-v8-r1-c1`). So the mapping falls
straight out of the drop: folder name → `study_id` + `consent_code`, that group's
`mapped-data/Person.tsv` → the identities. No SSTR or RDS join.

## What exists

- **ETL job** `generate-identity-consent-mapping`
  (`jobs/harmonized/GenerateIdentityConsentMappingJob`, 11 unit tests). Picks the newest
  drop deterministically when `--dataset-prefix` is blank. One combined CSV by default;
  `--per-study` splits per accession. Identities spanning two consent groups of one study
  → warning (`SUCCESS_WITH_WARNINGS`), not failure.
- **Standard EC2 runner** `etl-runners/generate-identity-consent-mapping/` (Jenkinsfile,
  Makefile, preflight, validate, terraform — same shape as the other six). Standalone:
  no orchestrator triggers it; run it when a new drop lands.
- **Jenkins job** `generate-identity-consent-mapping` at the **top level** (do NOT move it
  into a folder whose name has a space — see run #2 below).

## How to run it

Build with default parameters. `PREFLIGHT_ONLY=true` only when parameters changed.
Defaults: base `s3://nih-nhlbi-bdc-harmdata-exchange`, latest drop, role
`arn:aws:iam::714862078411:role/nih-nhlbi-TopMed-EC2Access-S3`, output
`s3://avillach-73-bdcatalyst-etl/dmcharmonizedexamples/output/` (**temporary** — see open
question). Green looks like: dataset `…-20260804`, 19 consent groups / 8 studies, the CSV
at the output prefix, Validate printing the counts.

## Credential model (the part worth understanding)

```
EC2 instance role: jenkins-s3-role (900)
  ├─ sts:AssumeRole → nih-nhlbi-TopMed-EC2Access-S3 (714/600)  → INPUT reads (in-JAR)
  └─ sts:AssumeRole → dbgap-etl (736)                          → OUTPUT writes (container
                                                                  default chain, 73 bucket)
```

`dbgap-etl` itself has NO sts:AssumeRole — never chain through it to reach NHLBI.
`jenkins-s3-role` cannot write the 73 bucket — never use it for outputs.
Never assume the NHLBI role from a laptop; access is tied to the instance profile.

## Run history (shakeout, 2026-09-04)

| Run | Result | Cause | Resolution |
|---|---|---|---|
| #2 | fail (make) | job in folder `__Harmonization Work` → space in workspace path breaks `common.mk` | job moved to Jenkins top level |
| #3 | green | PREFLIGHT_ONLY (validated NHLBI role access + latest-drop resolution) | — |
| #4 | exit 5 | `S3Uri.parse` rejects bucket-root listing | `77b78ac` — `parsePrefix` accepts bucket root |
| #5 | exit 4 | STS 403: assume ran as `dbgap-etl` (no sts:AssumeRole) instead of the instance role | code fix: STS call uses instance-profile credentials |

## Current state / next step

The STS fix is committed but **has not had a verifying run yet**. Next action: push (if not
already), re-run the Jenkins job with defaults, and read the Validate stage output. Reports
and full logs land in `s3://avillach-biodatacatalyst-deployments-3drb48r/etl-runner/{reports,logs}/…`
(readable with 900-account credentials, NOT with dbgap-etl).

## Open question (blocks closing the ticket)

Where does the mapping permanently live — S3 file or a table in the RDS `etl` schema? The
current output prefix is a placeholder; the job can target either. Decide on ALS-12727,
repoint `OUTPUT` (and add a loader job if RDS wins), then delete this file.
