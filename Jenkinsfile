// =============================================================================
// PERMANENT ETL PIPELINE -- the ongoing ingestion surface.
//
// Scope: only jobs whose Job.type() is JobType.PERMANENT. One-off migrations live in
// /Jenkinsfile.migration, which is deleted once its migrations have run everywhere.
//
// Structure: the DAG lives here, not in the JAR. Each permanent job is one stage that triggers
// that job's own pipeline, which owns provisioning its ephemeral runner, validating its inputs and
// outputs, and tearing itself down. A stage runs only if the one above it succeeded. The stage
// logic itself lives in etl-runners/common/orchestrator.groovy (loaded in 'Build'), keeping this
// pipeline {} block well under the JVM's 64 KB method limit.
//
// The participant database is a long-lived PostgreSQL 16 container on the Jenkins host.
// 'Start participant DB' makes sure it is up (see OVERWRITE_DB, DB_RESTORE_FROM and
// IF_LAST_RUN_FAILED) before the first DB-backed stage, and post { always } backs it up to S3 --
// as the latest clean backup only when this build succeeded. It is never torn down. See
// docs/PARTICIPANT_DB.md.
//
// Build and test run here once, as the gate for the whole run. Downstream jobs are invoked with
// SKIP_TESTS=true so the same commit's suites are not re-run per study. The runner jobs rebuild
// nothing: 'Package runner image' builds the runner image once and passes it to every one of them
// (SSTR load, global AllConcepts, VCF indexes, per-study allConcepts, merge, manifest and QA) as
// PREBUILT_IMAGE_TAR.
//
// Per-study stages: SSTR load runs one study at a time, or STUDY_CONCURRENCY at once with
// PARALLEL_STUDY_LOADS. Per-study allConcepts only reads the participant DB, so it always runs
// STUDY_CONCURRENCY at once, largest study first, each with a timeout scaled to its decoded data.
//
// Per-study allConcepts: each unprocessed study's decoded data is turned into per-consent
// allConcepts files under {DATA_ROOT}/{study_id}/allConcepts/ (overwritten in place; the bucket is
// versioned), which Merge AllConcepts then reads. A generation failure halts the pipeline
// before the merge, so a merged file never mixes this run's output with a missing study.
//
// Trigger modes (both supported by this pipeline):
//   STUDY_ID blank    sweep every study marked "Data is ready to process" = Yes in managed inputs.
//                     A study whose inputs are missing is excluded (build UNSTABLE), not fatal.
//   STUDY_ID set      load exactly that study (the reload/manual entry path); INPUT overrides
//                     the SSTR file discovered under DATA_ROOT. A missing input fails the build.
//
// Last stage: QA consent counts (etl-runners/qa-consent-counts/) checks every ready study's
// participants per consent group against dbGaP; a discrepancy makes the build UNSTABLE.
//
// End of every run: downstream-artifacts/pipeline-report/ (etl-runners/pipeline-report/), archived
// and uploaded to s3://<DATA_ROOT bucket>/etl-runner/reports/permanent-pipeline/<build-tag>/. Its
// first section is the consent count QA's discrepancies.
//
// Exit codes the stages gate on (see ExitCode.java):
//   0 success | 1 unknown | 2 validation | 3 data | 4 infrastructure | 5 config
// =============================================================================

// The loaded etl-runners/common/orchestrator.groovy. Loaded first thing in 'Build'; orch() loads it
// on demand too, so post { always } works even when the build failed before that.
ORCH = null

pipeline {
    agent any

    tools {
        jdk 'jdk-25'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '20'))
        disableConcurrentBuilds()
        // No pipeline-level timeout: a pipeline-level options { timeout } also covers post { },
        // where it would cut short the participant DB backup. The 'ETL' stage below carries the
        // backstop instead, and post bounds its own steps.
    }

    parameters {
        string(name: 'STUDY_ID', defaultValue: '',
               description: 'Blank sweeps every ready study from managed inputs. Set to a phs###### to load exactly one study (the reload/manual entry path).')
        string(name: 'INPUT', defaultValue: '',
               description: 'Only used with STUDY_ID: the SSTR input URI for that study, overriding discovery under DATA_ROOT.')
        string(name: 'MANAGED_INPUTS', defaultValue: '',
               description: 'REQUIRED. The managed inputs CSV, an s3:// URI (UTF-8, no byte-order mark). The study list is resolved from it here, and it is passed to every later stage -- with any study excluded for missing inputs marked not ready.')
        string(name: 'DATA_ROOT', defaultValue: 's3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/BAM_testing',
               description: 'Root of the per-study folders, one per study id. Must be an s3:// URI on a versioned bucket. Each study\'s SSTR is discovered in {DATA_ROOT}/{study_id}/rawData/ as sstr_{study_id}.{v}.txt (case-insensitive; the legacy BDC-ingestion-only__sstr_ prefix is also accepted) -- the same rule participants-migration uses. Per-consent allConcepts files are written to, and merged in, {DATA_ROOT}/{study_id}/allConcepts/c{code}/.')
        string(name: 'BATCH_SIZE', defaultValue: '1000',
               description: 'Rows per batch insert, passed to every permanent job')
        string(name: 'SSTR_JOB', defaultValue: 'sstr-populate-rds-participants',
               description: 'Jenkins job that runs etl-runners/sstr-populate-rds-participants/Jenkinsfile')
        booleanParam(name: 'RUN_INTEGRATION_TESTS', defaultValue: true,
               description: 'Run the Testcontainers *IT suites (needs a Docker daemon on the agent). These are the only checks that assert real DB state.')
        booleanParam(name: 'CONTINUE_ON_STUDY_FAILURE', defaultValue: true,
               description: 'Keep loading the remaining studies when one fails, then fail the build with a summary. Safe: each study is loaded in its own transaction, scoped to its own study_id.')
        booleanParam(name: 'PARALLEL_STUDY_LOADS', defaultValue: false,
               description: 'Run the SSTR loads concurrently, one runner each, at most STUDY_CONCURRENCY at a time. Correct (see Concurrency in docs/JENKINS.md) but off by default: studies sharing subjects serialize on those rows anyway, and sequential keeps the participant DB write load predictable and the log readable. Per-study allConcepts always runs STUDY_CONCURRENCY at a time (it only reads the DB).')
        string(name: 'STUDY_CONCURRENCY', defaultValue: '5',
               description: 'Most per-study builds (and EC2 runners) at once, 1-50: per-study allConcepts always, the SSTR load with PARALLEL_STUDY_LOADS. Every study still runs; the rest wait for a free slot.')
        booleanParam(name: 'PREFLIGHT_ONLY', defaultValue: false,
               description: 'Validate every study\'s inputs and stop, without provisioning anything (the participant DB included)')
        string(name: 'ALL_CONCEPTS_JOB', defaultValue: 'generate-global-all-concepts',
               description: 'Jenkins job that runs the generate-global-all-concepts runner')
        string(name: 'ALL_CONCEPTS_OUTPUT', defaultValue: 's3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/global_allconcepts/',
               description: 'Output location for global_AllConcepts.csv. Must be an s3:// URI: a local path resolves inside the runner container and is destroyed with it.')
        string(name: 'VCF_INDEXES_JOB', defaultValue: 'create-vcf-indexes',
               description: 'Jenkins job that runs the create-vcf-indexes runner')
        string(name: 'VCF_INDEXES_OUTPUT', defaultValue: 's3://bdc-etl-data-d0d6191/avillach-73-bdcatalyst-etl/vcf_indexes/',
               description: 'Output location for vcfIndex.tsv and SampleIds.csv. Must be an s3:// URI.')
        string(name: 'ALL_CONCEPTS_GENERATOR_JOB', defaultValue: 'all-concepts-data-generator',
               description: 'Jenkins job that runs etl-runners/all-concepts-data-generator/Jenkinsfile')
        string(name: 'DECODED_DATA_DIR', defaultValue: 'decoded_data',
               description: 'Folder, relative to {DATA_ROOT}/{study_id}/, holding a study\'s decoded data CSVs')
        string(name: 'CONCEPT_MAPPING_FILE', defaultValue: 'mappings/mapping2.csv',
               description: 'File, relative to {DATA_ROOT}/{study_id}/, holding a study\'s concept mapping')
        booleanParam(name: 'SKIP_ANALYSIS', defaultValue: false,
               description: 'Pass --skip-analysis to the generator: use the mapping file\'s data types as-is instead of re-analysing the decoded data')
        string(name: 'GENERATOR_TIMEOUT_BASE_SECONDS', defaultValue: '1800',
               description: 'Size-scaled per-study allConcepts timeout (exit 124 when exceeded): this plus GENERATOR_TIMEOUT_SECONDS_PER_GB per GiB of the study\'s decoded data, never less than 7200 (2 h) nor more than 12600 (3.5 h, inside the generator job\'s own 4 h limit).')
        string(name: 'GENERATOR_TIMEOUT_SECONDS_PER_GB', defaultValue: '600',
               description: 'Seconds allowed per GiB of decoded data (default 10 minutes). Raise it if large studies time out while still making progress.')
        string(name: 'MERGE_ALLCONCEPTS_PARALLELISM', defaultValue: '8',
               description: 'merge-allconcepts --parallelism: consent folders merged at once, 1-16')
        string(name: 'MERGE_ALLCONCEPTS_TIMEOUT_SECONDS', defaultValue: '14400',
               description: 'How long merge-allconcepts may run before it is failed as stuck (exit 124), at most 28800 (8 h, inside the merge job\'s own 9 h limit; a retry after exit 4 gets only what is left of the 9 h)')
        string(name: 'MERGE_ALLCONCEPTS_INSTANCE_TYPE', defaultValue: 'c5.xlarge',
               description: 'EC2 instance type for merge-allconcepts. Passed explicitly: a parameter left out of the build call gets the default the child job registered on its previous build, not the one in its current Jenkinsfile.')
        string(name: 'MERGE_ALLCONCEPTS_JOB', defaultValue: 'merge-allconcepts',
               description: 'Jenkins job that runs the merge-allconcepts runner')
        string(name: 'ALLCONCEPTS_MANIFEST_JOB', defaultValue: 'generate-allconcepts-manifest',
               description: 'Jenkins job that writes the versioned allConcepts_manifest.csv at DATA_ROOT (before QA consent counts)')
        string(name: 'QA_CONSENT_COUNTS_JOB', defaultValue: 'qa-consent-counts',
               description: 'Jenkins job that checks every ready study\'s participants per consent group (participant DB and this run\'s loads) against dbGaP (last stage). Its runner needs HTTPS egress to www.ncbi.nlm.nih.gov.')
        string(name: 'DB_START_JOB', defaultValue: 'participant-db-start',
               description: 'Jenkins job that runs etl-runners/participant-db/Jenkinsfile.start')
        string(name: 'DB_BACKUP_JOB', defaultValue: 'participant-db-backup',
               description: 'Jenkins job that runs etl-runners/participant-db/Jenkinsfile.backup (end of every run)')
        booleanParam(name: 'OVERWRITE_DB', defaultValue: false,
               description: 'Replace the participant database before this run with DB_RESTORE_FROM (blank = the latest clean backup), whether or not one is up. A running database is saved to participant_db_pre_overwrite.tar first.')
        string(name: 'DB_RESTORE_FROM', defaultValue: '',
               description: 'What a new or replaced participant database is loaded from: blank = the latest clean backup, "none" = a fresh schema, a file under backups/, or an s3:// URI. Ignored when the database is up and OVERWRITE_DB is false.')
        choice(name: 'IF_LAST_RUN_FAILED', choices: ['restore-clean', 'continue'],
               description: 'If the previous pipeline run failed, its writes are still in the live database: restore-clean (default) replaces it from the latest clean backup before this run; continue builds on it.')
        choice(name: 'ENV', choices: ['development'],
               description: 'Target environment. Selects etl-runners/environments/<ENV>.tfvars for account, network, and DB settings.')
    }

    environment {
        ENV        = "${params.ENV ?: 'development'}"
        AWS_REGION = 'us-east-1'
    }

    stages {
        // Every stage runs inside this one, for its timeout: a backstop for the whole run that,
        // unlike a pipeline-level one, leaves post { } (the DB backup) its own time. Each child job
        // has its own, tighter timeout. 72 h: a sweep of ~30 new studies boots two runners per study
        // (SSTR load sequentially by default, then allConcepts), and the merge alone may take 4 h.
        stage('ETL') {
            options {
                timeout(time: 72, unit: 'HOURS')
            }
            stages {

                stage('Build') {
                    steps {
                        script {
                            // The workspace persists between builds. Everything under
                            // downstream-artifacts/ must come from THIS build: post { always }
                            // archives whatever is there as this run's.
                            sh 'rm -rf downstream-artifacts'
                            orch()
                        }
                        sh './mvnw -B clean package -DskipTests'
                    }
                }

                stage('Tests') {
                    steps {
                        // Jenkins runs in a podman container that reaches the HOST's podman through
                        // the mounted /var/run/docker.sock. Ryuk (Testcontainers' cleanup container)
                        // would bind that path from the host, where it does not exist, and never
                        // start; and the test containers publish their ports on the host, which is
                        // not this container's localhost. Containers are still removed when the JVM
                        // exits normally.
                        withEnv(['TESTCONTAINERS_RYUK_DISABLED=true',
                                 'TESTCONTAINERS_HOST_OVERRIDE=host.containers.internal']) {
                            sh(params.RUN_INTEGRATION_TESTS ? './mvnw -B verify' : './mvnw -B test')
                        }
                        // The participant database scripts and the orchestrator's own helpers
                        // (stubbed docker/aws; seconds, no AWS or runtime needed).
                        sh 'make -C etl-runners/participant-db test'
                        sh 'make -C etl-runners/pipeline-report test'
                        sh 'make -C etl-runners/qa-consent-counts test'
                    }
                    post {
                        always {
                            junit testResults: 'target/surefire-reports/*.xml,target/failsafe-reports/*.xml',
                                  allowEmptyResults: true
                        }
                    }
                }

                stage('Resolve studies') {
                    steps {
                        script {
                            // Validates every parameter, reads managed inputs, probes each study's
                            // inputs in S3 (in parallel), and excludes a sweep's studies that miss one.
                            UNPROCESSED_STUDIES = orch().resolveStudies().unprocessed
                        }
                    }
                }

                // One runner image for every downstream runner build. Each would otherwise compile
                // the JAR and build, save and upload its own copy of the same image on this host --
                // hundreds of times over in a large sweep. Built even when no study is unprocessed:
                // global AllConcepts, the merge and the manifest still run. Before the DB starts, so
                // a packaging failure leaves the participant DB untouched.
                stage('Package runner image') {
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script { orch().packageRunnerImage() }
                    }
                }

                stage('Start participant DB') {
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script { orch().startParticipantDb() }
                    }
                }

                // -------------------------------------------------------------
                // Permanent job stages. One per PERMANENT job, in the order they must run.
                // -------------------------------------------------------------

                stage('Load SSTR participants') {
                    when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
                    steps {
                        script {
                            orch().runPerStudyStage(
                                title: 'SSTR load',
                                key: 'sstr',
                                job: params.SSTR_JOB,
                                studies: UNPROCESSED_STUDIES,
                                workers: orch().sstrConcurrency(),
                                paramsFor: { s -> [
                                    string(name: 'STUDY_ID', value: s.studyId),
                                    string(name: 'INPUT', value: s.input),
                                    string(name: 'BATCH_SIZE', value: params.BATCH_SIZE),
                                    string(name: 'RUN_ID', value: "${env.BUILD_TAG}-${s.studyId}"),
                                    string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                    string(name: 'ENV', value: params.ENV),
                                    booleanParam(name: 'SKIP_TESTS', value: true),
                                    booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                                ] },
                                onFailure: { failed ->
                                    "SSTR load failed for: ${failed}. Each study is loaded in its own transaction, so a " +
                                    'failed study left the participant DB unchanged — fix its input and re-run just that ' +
                                    'study with STUDY_ID.'
                                })
                        }
                    }
                }

                stage('Generate global AllConcepts') {
                    // No pre-flight mode of its own: it reads the participant DB, which
                    // PREFLIGHT_ONLY does not start.
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script {
                            echo 'Generating global_AllConcepts.csv from populated database...'
                            orch().runDownstream(params.ALL_CONCEPTS_JOB, [
                                string(name: 'OUTPUT', value: params.ALL_CONCEPTS_OUTPUT),
                                string(name: 'MANAGED_INPUTS', value: orch().laterStageManagedInputs()),
                                string(name: 'RUN_ID', value: "${env.BUILD_TAG}-all-concepts"),
                                string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                string(name: 'ENV', value: params.ENV),
                                booleanParam(name: 'SKIP_TESTS', value: true),
                            ], 'downstream-artifacts/all-concepts')
                        }
                    }
                }

                stage('Create VCF indexes') {
                    when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
                    steps {
                        script {
                            echo 'Creating VCF indexes from genomic data...'
                            orch().runDownstream(params.VCF_INDEXES_JOB, [
                                string(name: 'OUTPUT', value: params.VCF_INDEXES_OUTPUT),
                                string(name: 'MANAGED_INPUTS', value: orch().laterStageManagedInputs()),
                                string(name: 'RUN_ID', value: "${env.BUILD_TAG}-vcf-indexes"),
                                string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                string(name: 'ENV', value: params.ENV),
                                booleanParam(name: 'SKIP_TESTS', value: true),
                                booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                            ], 'downstream-artifacts/vcf-indexes')
                        }
                    }
                }

                stage('Generate per-study AllConcepts') {
                    when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
                    steps {
                        script {
                            orch().runPerStudyStage(
                                title: 'Per-study allConcepts',
                                key: 'per-study-all-concepts',
                                job: params.ALL_CONCEPTS_GENERATOR_JOB,
                                // Largest first, so the longest builds do not trail the stage alone.
                                studies: orch().largestFirst(UNPROCESSED_STUDIES),
                                workers: orch().studyConcurrency(),
                                paramsFor: { s -> [
                                    string(name: 'STUDY_ID', value: s.studyId),
                                    string(name: 'DATA_DIR', value: s.dataDir),
                                    string(name: 'MAPPING', value: s.mapping),
                                    string(name: 'OUTPUT', value: params.DATA_ROOT.trim()),
                                    booleanParam(name: 'SKIP_ANALYSIS', value: params.SKIP_ANALYSIS),
                                    string(name: 'JOB_TIMEOUT_SECONDS', value: "${orch().generatorTimeoutSeconds(s.decodedBytes)}"),
                                    string(name: 'RUN_ID', value: "${env.BUILD_TAG}-allconcepts-${s.studyId}"),
                                    string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                    string(name: 'ENV', value: params.ENV),
                                    booleanParam(name: 'SKIP_TESTS', value: true),
                                    booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                                ] },
                                // Halts here, before Merge AllConcepts: a merge run with a study
                                // missing or half-written would publish merged files that silently
                                // lack its rows.
                                onFailure: { failed ->
                                    "Per-study allConcepts failed for: ${failed}. Merge AllConcepts was not run. Fix the " +
                                    'study and re-run it with STUDY_ID (its files are overwritten in place).'
                                })
                        }
                    }
                }

                stage('Merge AllConcepts') {
                    // No pre-flight mode of its own: skipped rather than provisioned under PREFLIGHT_ONLY.
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script {
                            echo 'Merging per-consent allConcepts files where needed...'
                            // A STUDY_ID reload loads that study whatever its ready flag, so merge
                            // exactly it; a sweep merges every ready (and not excluded) study. (The
                            // manifest still lists only ready studies.)
                            orch().runDownstream(params.MERGE_ALLCONCEPTS_JOB, [
                                string(name: 'INPUT', value: params.DATA_ROOT.trim()),
                                string(name: 'MANAGED_INPUTS', value: orch().laterStageManagedInputs()),
                                string(name: 'STUDY_IDS', value: params.STUDY_ID?.trim() ?: ''),
                                string(name: 'PARALLELISM', value: params.MERGE_ALLCONCEPTS_PARALLELISM.trim()),
                                string(name: 'JOB_TIMEOUT_SECONDS', value: params.MERGE_ALLCONCEPTS_TIMEOUT_SECONDS.trim()),
                                string(name: 'INSTANCE_TYPE', value: params.MERGE_ALLCONCEPTS_INSTANCE_TYPE.trim()),
                                string(name: 'RUN_ID', value: "${env.BUILD_TAG}-merge-allconcepts"),
                                string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                string(name: 'ENV', value: params.ENV),
                                booleanParam(name: 'SKIP_TESTS', value: true),
                            ], 'downstream-artifacts/merge-allconcepts')
                        }
                    }
                }

                stage('AllConcepts manifest') {
                    // Last stage: verifies the global AllConcepts file (ALL_CONCEPTS_OUTPUT) and
                    // every ready consent group's MERGED file, and publishes the versioned
                    // {DATA_ROOT}/allConcepts_manifest.csv for production ingestion. Writes nothing
                    // (and fails the build) if any of them is missing or stale, so ingestion never
                    // gets a bad manifest.
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script {
                            orch().runDownstream(params.ALLCONCEPTS_MANIFEST_JOB, [
                                string(name: 'INPUT', value: params.DATA_ROOT.trim()),
                                string(name: 'GLOBAL_ALLCONCEPTS', value: params.ALL_CONCEPTS_OUTPUT.trim()),
                                string(name: 'MANAGED_INPUTS', value: orch().laterStageManagedInputs()),
                                string(name: 'RUN_ID', value: "${env.BUILD_TAG}-allconcepts-manifest"),
                                string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                string(name: 'ENV', value: params.ENV),
                                booleanParam(name: 'SKIP_TESTS', value: true),
                            ], 'downstream-artifacts/allconcepts-manifest',
                               ': the manifest was NOT updated. Its report lists every missing or stale MERGED file.')
                        }
                    }
                }

                stage('QA consent counts') {
                    // Last: every ready study's participants per consent group (participant DB, and
                    // this run's SSTR loads) against dbGaP's counts for its version. Read-only.
                    // Discrepancies make the build UNSTABLE; dbGaP not answering after retries fails it.
                    // The end-of-run report puts the discrepancies at its top.
                    when { expression { !params.PREFLIGHT_ONLY } }
                    steps {
                        script { orch().runQaConsentCounts(params.QA_CONSENT_COUNTS_JOB) }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Bounded on its own: the DB backup job has a 3 h timeout of its own.
                try {
                    timeout(time: 4, unit: 'HOURS') {
                        orch().backupParticipantDb(params.DB_BACKUP_JOB, params.ENV)
                    }
                } catch (err) {
                    currentBuild.result = 'FAILURE'
                    echo "ERROR: the participant DB backup did not finish (${err}). Re-run ${params.DB_BACKUP_JOB} before the next pipeline run."
                }
                // After the backup, so the report carries the build's final result. Never changes it.
                try {
                    timeout(time: 15, unit: 'MINUTES') {
                        orch().writePipelineReport()
                    }
                } catch (err) {
                    echo "WARNING: the pipeline report was not written (${err})."
                }
                orch().removeRunnerImage()
            }
            archiveArtifacts artifacts: 'downstream-artifacts/**', allowEmptyArchive: true
        }
        failure {
            echo 'Permanent ETL pipeline FAILED. downstream-artifacts/pipeline-report/pipeline-report.md lists each ' +
                 'study\'s outcome and why; each study\'s own build page has its JSON report and runner log, and the ' +
                 'exit code there says whether this was validation (2), data (3, study rolled back), infrastructure ' +
                 '(4, retryable), or config (5).'
        }
        unstable {
            echo 'Permanent ETL pipeline completed WITH WARNINGS: studies were excluded for missing inputs, or a ' +
                 'stage reported warnings (typically 0 new participants on a reload). downstream-artifacts/' +
                 'pipeline-report/pipeline-report.md lists every study needing follow-up and why.'
        }
        success {
            echo 'Permanent ETL pipeline completed. Every study loaded and passed its output validation.'
        }
    }
}

// The loaded etl-runners/common/orchestrator.groovy (loaded on first use).
def orch() {
    if (ORCH == null) {
        ORCH = load 'etl-runners/common/orchestrator.groovy'
    }
    return ORCH
}
