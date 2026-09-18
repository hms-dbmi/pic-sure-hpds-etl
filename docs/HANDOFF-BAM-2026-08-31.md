# BAM Migration Pipeline — Handoff Notes (2026-08-31)

Companion to the fix in this branch. Written for handback: everything here is
readable without prior context, and every claim names its evidence.

## What this branch changes

**Two bugs, one root class: paths that resolve inside the runner container.**
Each ETL job runs in a Docker container (`WORKDIR /app`, only `/reports`
mounted) on its own ephemeral EC2 instance — so any non-`s3://` path a Jenkins
pipeline passes it resolves against the container filesystem, not the Jenkins
workspace.

### 1. MAPPING handoff (ALS-12159) — pipeline-halting

`Jenkinsfile.migration` passed each study's `{sid}_hpds_id_mapping.csv` to the
`split-allconcepts` job as an orchestrator-workspace path. The container can
never see that file → CONFIG_ERROR (exit 5) on every run. Commit `7c145bd`
fixed file *discovery* in the orchestrator (copyArtifacts `flatten:true`) but
not the handoff.

**Evidence** (runner reports in the stack bucket,
`etl-runner/reports/split-allconcepts/`): every split job in orchestrator
builds #41/#42 (Aug 28) exited `5 / CONFIG_ERROR` with
`S3 object not found: s3://avillach-73-bdcatalyst-etl/__migration__/current/{sid}_hpds_id_mapping.csv`
— the pre-`7c145bd` fallback URI. That prefix has no object versions and no
delete markers: nothing was ever uploaded there.

**Fix:** the orchestrator uploads each mapping CSV to a new
`MAPPING_UPLOAD_BASE` prefix (default
`s3://avillach-73-bdcatalyst-etl/__migration__/mappings/<build-tag>/`) after
the copyArtifacts gate, and passes the `s3://` URI.

**Why this bucket:** the container performs ALL S3 I/O as
`arn:aws:iam::736265540791:role/dbgap-etl` (single `S3Client`,
`AwsConfig.java`). Policy `dbgap-etl` v10 grants Put/Get/DeleteObject on
`avillach-73-bdcatalyst-etl/*`. The role can NOT read the stack bucket where
runner reports land — do not route inter-runner artifacts through it, and the
`-r2` infra-retry suffix makes the reports prefix non-deterministic anyway.

### 2. Split output silently discarded (ALS-12160) — latent

`SPLIT_OUTPUT` / `OUTPUT` defaulted to `./output` = `/app/output` in the
container: unmounted, destroyed with the instance. A green run wrote its
report, validated, and threw away every per-consent CSV. Defaults now point at
`s3://avillach-73-bdcatalyst-etl/__migration__/split_allconcepts/`. (Friday's
runs overrode output to `s3://…/BAM_testing/outputs/splits/`, so this bites
only whoever trusts the default.)

Also added: fail-fast guard in the split runner's Init stage — non-`s3://`
INPUT/MAPPING/OUTPUT errors immediately instead of after ~20 minutes of
provisioning.

## Verification runbook (not yet run)

Friday's real staging (recovered from `container-status.json` of build #37-r2):

```
MANAGED_INPUTS = s3://avillach-73-bdcatalyst-etl/BAM_testing/general/BAM_Managed_Inputs.csv
DATA_FOLDER    = s3://avillach-73-bdcatalyst-etl/BAM_testing/
```

(The `__migration__/…` Jenkins defaults were never used and that prefix is empty.)

1. `new-hpds-etl-participant-migration-pipeline` with the params above and
   `PREFLIGHT_ONLY=true` — expect split preflight UNSTABLE (soft mapping
   check), nothing provisioned.
2. Single-study run (trim a copy of the managed-inputs CSV to one ready
   study). Success = mapping object under `__migration__/mappings/<tag>/`,
   split exit 0, non-zero `rowsPerConsent` in the report JSON, per-consent
   CSVs present at the S3 output.
3. Full sweep.

**Watch item:** build #37-r2 (participants-migration) itself exited
`4 / INFRASTRUCTURE_ERROR` — "Failed to list
s3://avillach-73-bdcatalyst-etl/BAM_testing/whi/rawData" — after 64 min, yet
that prefix exists and lists fine today. Likely transient credentials mid-run
(the role-assume fixes landed afterwards). If it recurs, that's the next
debugging thread. Its mapping CSVs for 23 studies are preserved under
`etl-runner/reports/participants-migration/…-37-participants-r2/`.

## Settled questions

- **SSTR header contract (ALS-12158):** a real export
  (`BAM_testing/whi/rawData/SSTR__sstr_phs000281.v8.txt`) carries
  `SUBJECT_ID, SAMPLE_ID, CONSENT, SEX, consent_abbreviation,
  dbgap_subject_repository, dbgap_subject_id, dbgap_sample_id, biosample_id, …`
  — the job's required columns (`dbgap_subject_id`, `dbgap_sample_id`,
  `CONSENT`, `consent_abbreviation`) all present verbatim. Code is correct;
  the ticket's `submitted_subject_id`/`consent_code` wording was shorthand.

## Known remaining work (tracked in Jira)

- ALS-12163 recency-based merge: not implemented (design proposed on ticket).
- Integration tests: sstr→global-concepts e2e (ALS-12176), VCF header (ALS-12178).
- SSTR ragged-row test; non-standard cases (DCC-harmonized / tutorial-biolincc
  / multi) parsed from managed inputs but acted on by no job — needs requirements.
- No runner dir for `single-consent-data-populate-rds-participants` (job
  exists and is enabled).
- Stale docs: `Jenkinsfile.permanent` referenced in 7 places but doesn't exist
  (permanent orchestrator is `/Jenkinsfile`); `etl-runners/README.md` lists 2
  of 6 runners; `consents.csv` references in participants-migration docs.

---

# Update 2026-09-03: migration pipeline verified end-to-end

Full-sweep run (orchestrator #65, 2026-09-02) completed: **28/28 studies via the
SSTR sub-job, 0 direct; all 28 splits SUCCESS; global allConcepts fully green
(consent abbreviations now complete from SSTRs); VCF indexes green for all 9
genomic studies.** Artifacts verified in S3: 28 mapping CSVs
(`__migration__/mappings/…-65/`), 28 split study prefixes, 426 MB
`global_AllConcepts.csv`, 36 VCF index/sample files.

Defects found and fixed since the original handoff (each with its own commit
message telling the story): MAPPING handoff (local path to a container),
split output discarded (unmounted default), stale Jenkins parameter defaults
(trim + guards), phantom `avillach-etl` output bucket, hollow preflight checks,
workspace report accumulation, missing managed-inputs wiring in the vcf runner,
hard-failure on empty genomic workloads, split OOM on parent studies
(streaming rewrite), S3 5 GiB PutObject cap (multipart), deterministic-4xx
retry misclassification, SSTR discovery casing (three naming families),
missing submitted SAMPLE_ID/NWD sample loading, and `build(propagate:true)`
halting the orchestrator on a merely-UNSTABLE downstream.

Operational conventions now in force: STUDY_FILTER for per-study reruns;
staging filename convention `sstr_{phs}.{v}.txt` verbatim from NHLBI
(`BDC-ingestion-only/` key in the per-study ingest buckets, accounts
600168050588/714862078411); parameter changes to a Jenkinsfile take effect via
a PREFLIGHT_ONLY run; artifacts between runners travel only through the 73
bucket (the container role cannot read the stack bucket).

Known open items: one benign validation warning (phs003946: 156 SSTR subjects
have no legacy ids — new to HPDS, nothing to migrate); cohort-wide
PatientMapping warning noise (refinement queued); remaining test contract
(ALS-12172/12176/12178); ALS-12163 merge job unbuilt; docs refresh; WHI WGS
SSTR question (D-format sample ids carry no NWD names).

---

# Update 2026-09-04: harmonized consent mapping job (ALS-12727)

New PERMANENT job `generate-identity-consent-mapping` + standard ephemeral-EC2
runner, readable cold:

**What it does.** The DMC harmonization drops in
`s3://nih-nhlbi-bdc-harmdata-exchange/BDC-DMC-Harmonization-Examples-YYYYMMDD/`
are partitioned by consent group, and each group's directory name carries the
study accession and consent code (e.g.
`nih-nhlbi-topmed-parent-aric-phs000280-v8-r1-c1`). The job streams each
group's `mapped-data/Person.tsv` and emits
`Person.Identity,study_id,consent_code` — the mapping ALS-12727 asks for —
without any SSTR or RDS join. With no `--dataset-prefix` it selects the newest
drop deterministically (max date). One combined CSV by default; `--per-study`
splits per accession. An identity appearing in more than one consent group of
the same study is a warning (SUCCESS_WITH_WARNINGS), not a failure.

**Where.** Job: `jobs/harmonized/GenerateIdentityConsentMappingJob` (11 unit
tests; they mock the pull with local trees shaped like the drop). Runner:
`etl-runners/generate-identity-consent-mapping/` (full pattern: Jenkinsfile /
Makefile / preflight / validate / terraform; standalone — deliberately NOT in
the orchestrator DAG). Enablement flag in `application.yml`.

**Credentials.** The exchange bucket is readable only under
`arn:aws:iam::714862078411:role/nih-nhlbi-TopMed-EC2Access-S3` (same role name
in both NHLBI accounts); the job assumes it in-process via `--role-arn`
(`AssumedRoleS3Clients`) for input reads only. Output writes go through the
container's default chain (`dbgap-etl`, tied to the instance profile) into the
73 bucket. The agent-side preflight checks the exchange bucket under an
`nhlbi-exchange` profile and the output bucket under `dbgap-etl`.

**Running it.** Jenkins job `generate-identity-consent-mapping` (top level, NOT
inside a folder — see shakeout #2) pointing at the runner Jenkinsfile; first run
`PREFLIGHT_ONLY=true` to register parameters, then a real run. Default output
`s3://avillach-73-bdcatalyst-etl/dmcharmonizedexamples/output/` is a
**temporary location** — where the mapping should permanently live (S3 file vs
RDS table in the `etl` schema) is the open decision on ALS-12727. The RDS
credential fetch in the shared runner user-data still happens even though this
job never touches the database; harmless, but a known inefficiency.

**Shakeout ledger (2026-09-04, real Jenkins runs #2–#5):**

1. **Run #2 — workspace path with a space.** The job was created inside the
   Jenkins folder `__Harmonization Work`, so the workspace path contained a
   space and the whole make layer (`common.mk` unquoted paths) broke at
   `ensure-terraform`. Not fixed in code (every runner assumes space-free
   workspaces); the job was **moved to the Jenkins top level** like the other
   seven. Keep it there.
2. **Run #4 — bucket-root listing.** `IoResolver.S3Uri.parse` requires a key
   after the bucket, and this is the first job to LIST a bucket root
   (`s3://nih-nhlbi-bdc-harmdata-exchange/`) → CONFIG_ERROR in 56 ms. Fixed in
   `77b78ac`: `S3Uri.parsePrefix` accepts the bucket root for prefix listings;
   object addressing stays strict.
3. **Run #5 — STS 403 on the in-process assume.** The container's env sets
   `AWS_PROFILE=etl-cross-account` (the dbgap-etl chain), so the job's STS call
   ran as `dbgap-etl` — which carries **no sts:AssumeRole at all** (policy
   verified). The principal the NHLBI exchange roles actually trust is the
   **instance role** `jenkins-s3-role` (900): its `sts-etl-cicd-policy`
   explicitly allows AssumeRole on both `nih-nhlbi-TopMed-EC2Access-S3` roles
   (714862078411 and 600168050588) plus `dbgap-etl` — which is also why the
   agent-side preflight passed. Fixed in code (no IAM change):
   `StsAssumedRoleS3Clients` now makes the STS call with the instance-profile
   credentials (IMDS, hop limit already 2), falling back to the default chain
   off-EC2. Output writes are untouched: still the dbgap-etl default chain into
   the 73 bucket (`jenkins-s3-role` itself can only write the deployments
   stack bucket).

**Next step (not yet run):** re-run the Jenkins job with default parameters —
no PREFLIGHT_ONLY needed, nothing about parameters changed. Expected green:
dataset `BDC-DMC-Harmonization-Examples-20260804`, 19 consent groups across
8 studies, `identity_consent_mapping.csv` at the temp output prefix, and the
Validate stage printing the row counts. UNSTABLE means identities spanning
consent groups of one study — mapping still written; raise the count with the
DMC. See also `docs/TEMP-ALS-12727-HANDBACK.md` (temporary, delete before
merge) for the zero-context version of all of this.
