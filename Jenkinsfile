// =============================================================================
// TEMPORARY MIGRATION PIPELINE -- one-off migrations off the legacy HPDS system.
//
// Scope: only jobs whose Job.type() is JobType.MIGRATION. Permanent ingestion lives in
// /Jenkinsfile.permanent so that scheduling, retention, notification, and the decision to delete
// stay independent between the two.
//
// When the migration is complete, delete:
//   - this file
//   - etl-runners/participants-migration/                (runner, Terraform, validation)
//   - src/.../jobs/migration/ParticipantsMigrationJob    (and its tests)
//   - the etl.pipelines.migrate-all entry in application.yml
// then rename /Jenkinsfile.permanent to /Jenkinsfile.
//
// Structure: the DAG lives here, not in the JAR. Each migration job is one stage that triggers
// that job's own pipeline, which owns provisioning its ephemeral runner, validating its inputs and
// outputs, and tearing itself down. A stage runs only if the one above it succeeded, so ordering
// between migrations is stage order.
//
// Build and test run here once, as the gate for the whole run. Downstream jobs are invoked with
// SKIP_TESTS=true: they rebuild the JAR in their own workspace but do not re-run suites this
// pipeline already ran on the same commit.
//
// Exit codes the stages gate on (see ExitCode.java):
//   0 success | 1 unknown | 2 validation | 3 data | 4 infrastructure | 5 config
// =============================================================================

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
        // A migration is a one-off against shared tables. Never run two at once.
        disableConcurrentBuilds()
        timeout(time: 8, unit: 'HOURS')
    }

    parameters {
        string(name: 'MANAGED_INPUTS',
               defaultValue: 's3://avillach-73-bdcatalyst-etl/__migration__/managed_inputs.csv',
               description: 'participants-migration --managed-inputs: study list CSV with "Study Abbreviated Name", "Study Identifier", "Data is ready to process"')
        string(name: 'DATA_FOLDER',
               defaultValue: 's3://avillach-73-bdcatalyst-etl/__migration__/current',
               description: 'participants-migration --data-folder: folder holding {studyid}_sstr.tsv, {ABV}_PatientMapping.v2.csv, and the shared consents.csv')
        string(name: 'BATCH_SIZE', defaultValue: '1000',
               description: 'Rows per batch insert, passed to every migration job')
        string(name: 'PARTICIPANTS_MIGRATION_JOB', defaultValue: 'hpds-etl/participants-migration',
               description: 'Jenkins job that runs etl-runners/participants-migration/Jenkinsfile')
        booleanParam(name: 'RUN_INTEGRATION_TESTS', defaultValue: true,
               description: 'Run the Testcontainers *IT suites (needs a Docker daemon on the agent). These are the only checks that assert real DB state, so leaving them on is strongly preferred before a migration.')
        booleanParam(name: 'PREFLIGHT_ONLY', defaultValue: false,
               description: 'Validate every migration input layout and stop, without provisioning anything')
    }

    environment {
        AWS_REGION = 'us-east-1'
    }

    stages {

        stage('Build') {
            steps {
                sh './mvnw -B clean package -DskipTests'
            }
        }

        // The gate for the whole run: nothing is provisioned until the suites pass.
        // `verify` runs surefire (*Test) and then failsafe (*IT, Testcontainers Postgres +
        // LocalStack); `test` runs surefire alone. There is no supported way to run only the
        // ITs, and the unit suites are fast, so the two modes are simply verify vs test.
        stage('Tests') {
            steps {
                sh(params.RUN_INTEGRATION_TESTS ? './mvnw -B verify' : './mvnw -B test')
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml,target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                }
            }
        }

        // ---------------------------------------------------------------------
        // Migration stages. One per MIGRATION job, in the order they must run.
        // ---------------------------------------------------------------------

        stage('Migrate participants') {
            steps {
                script {
                    // Triggers etl-runners/participants-migration/Jenkinsfile, which
                    // provisions the ephemeral runner, monitors the job's exit code, fetches
                    // the reports, and validates the mapping files. propagate: true means a
                    // non-zero ETL exit code fails this stage and halts the pipeline, exactly
                    // as an inline `sh java -jar` would have.
                    def downstream = build(
                        job: params.PARTICIPANTS_MIGRATION_JOB,
                        wait: true,
                        propagate: true,
                        parameters: [
                            string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS),
                            string(name: 'DATA_FOLDER',    value: params.DATA_FOLDER),
                            string(name: 'BATCH_SIZE',     value: params.BATCH_SIZE),
                            string(name: 'RUN_ID',         value: "${env.BUILD_TAG}-participants"),
                            booleanParam(name: 'SKIP_TESTS',     value: true),
                            booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                        ])

                    echo "participants-migration: ${downstream.result} (${downstream.absoluteUrl})"
                    env.PARTICIPANTS_BUILD = "${downstream.number}"

                    // A downstream UNSTABLE means the job succeeded but its validation raised
                    // warnings (rows skipped, a study reporting something unexpected).
                    // propagate does not carry that across, so carry it deliberately.
                    if (downstream.result == 'UNSTABLE') {
                        unstable('participants-migration completed with validation warnings')
                    }
                }
            }
            post {
                always {
                    script {
                        // Keep the migration's reports on the orchestrator build too, so one
                        // build page shows the whole run. Needs the Copy Artifact plugin.
                        try {
                            copyArtifacts(
                                projectName: params.PARTICIPANTS_MIGRATION_JOB,
                                selector: specific(env.PARTICIPANTS_BUILD ?: '0'),
                                target: 'downstream-artifacts/participants-migration',
                                optional: true)
                        } catch (err) {
                            echo "Could not copy downstream artifacts (${err.message}). " +
                                 "They remain on ${params.PARTICIPANTS_MIGRATION_JOB} #${env.PARTICIPANTS_BUILD}."
                        }
                    }
                }
            }
        }

        // Add the next migration as another stage; it runs only because the one above passed.
        //
        // stage('Migrate consents') {
        //     steps {
        //         script {
        //             build job: 'hpds-etl/consents-migration', wait: true, propagate: true,
        //                   parameters: [ /* that job's parameters */ ]
        //         }
        //     }
        // }
    }

    post {
        always {
            archiveArtifacts artifacts: 'downstream-artifacts/**', allowEmptyArchive: true
        }
        failure {
            echo "Migration pipeline FAILED at stage '${env.STAGE_NAME}'. The failing job's own build " +
                 "page has its JSON report, mapping files, and runner log; the exit code there says " +
                 "whether this was validation (2), data (3), infrastructure (4, retryable), or config (5)."
        }
        unstable {
            echo 'Migration pipeline completed WITH WARNINGS. Rows were skipped or a study reported ' +
                 'something unexpected — review the archived reports before treating the migration as done.'
        }
        success {
            echo 'Migration pipeline completed. Every migration stage passed its own output validation.'
        }
    }
}
