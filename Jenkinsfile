// Example declarative pipeline for the HPDS ETL jobs.
//
// The pipeline DAG lives HERE, not in the JAR: each job is one stage, the next stage
// runs only if the previous succeeded (exit code 0), and Jenkins owns retries,
// notifications, scheduling, and artifact archiving. The JAR just runs one job and
// exits with a meaningful code (see ExitCode).
//
// Exit codes the stages can branch on:
//   0 success | 1 unknown | 2 validation | 3 data | 4 infrastructure | 5 config
pipeline {
    agent any

    parameters {
        string(name: 'PARTICIPANTS_INPUT', defaultValue: 's3://hpds-migration/participants.csv',
               description: 'Input URI for the participants migration')
        string(name: 'RUN_ID', defaultValue: "${BUILD_TAG}", description: 'Correlation id for this run')
    }

    environment {
        // Provided by the Jenkins agent's IAM role / credentials store -- never hard-coded.
        AWS_REGION   = 'us-east-1'
        RDS_URL      = credentials('hpds-rds-url')
        RDS_USERNAME = credentials('hpds-rds-username')
        RDS_PASSWORD = credentials('hpds-rds-password')
        JAR          = 'target/hpds-etl.jar'
    }

    stages {
        stage('Build') {
            steps {
                sh './mvnw -B clean package'
            }
        }

        // Fast, no-infrastructure tests gate everything else.
        stage('Unit tests') {
            steps {
                sh './mvnw -B test'
            }
        }

        stage('Migrate participants') {
            steps {
                // A non-zero exit fails the stage and halts the pipeline automatically.
                sh """
                   java -jar ${JAR} \
                     --job=participants-migration \
                     --input=${params.PARTICIPANTS_INPUT} \
                     --run-id=${params.RUN_ID}
                """
            }
        }

        // Add the next job as another stage; it runs only because the one above passed.
        // stage('Migrate consents') { steps { sh "java -jar ${JAR} --job=consents-migration ..." } }
    }

    post {
        always {
            // Every run leaves an archivable JSON report per job.
            archiveArtifacts artifacts: 'reports/*.json', allowEmptyArchive: true
            junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
        }
        failure {
            echo "ETL pipeline failed at ${env.STAGE_NAME}. See archived reports for the validation detail."
        }
    }
}
