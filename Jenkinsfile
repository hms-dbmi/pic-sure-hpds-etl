// =============================================================================
// PERMANENT ETL PIPELINE -- the ongoing ingestion surface.
//
// Scope: only jobs whose Job.type() is JobType.PERMANENT. One-off migrations live in
// /Jenkinsfile.migration, which is deleted once its migrations have run everywhere.
//
// Structure: the DAG lives here, not in the JAR. Each permanent job is one stage that triggers
// that job's own pipeline, which owns provisioning its ephemeral runner, validating its inputs and
// outputs, and tearing itself down. A stage runs only if the one above it succeeded.
//
// Build and test run here once, as the gate for the whole run. Downstream jobs are invoked with
// SKIP_TESTS=true so the same commit's suites are not re-run per study.
//
// Trigger modes (both supported by this pipeline):
//   STUDY_ID blank    sweep every study marked "Data is ready to process" = Yes in managed inputs
//   STUDY_ID set      load exactly that study (the reload/manual entry path); INPUT overrides
//                     the default SSTR input URI derived from INPUT_BASE
//
// Exit codes the stages gate on (see ExitCode.java):
//   0 success | 1 unknown | 2 validation | 3 data | 4 infrastructure | 5 config
// =============================================================================

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '20'))
        disableConcurrentBuilds()
        timeout(time: 12, unit: 'HOURS')
    }

    parameters {
        string(name: 'STUDY_ID', defaultValue: '',
               description: 'Blank sweeps every ready study from managed inputs. Set to a phs###### to load exactly one study (the reload/manual entry path).')
        string(name: 'INPUT', defaultValue: '',
               description: 'Only used with STUDY_ID: overrides the SSTR input URI for that study. Required when the study has no SSTR file at the default INPUT_BASE location.')
        string(name: 'MANAGED_INPUTS', defaultValue: '',
               description: 'Override for the managed inputs CSV URI. Blank uses the configured etl.managed-inputs.uri.')
        string(name: 'INPUT_BASE', defaultValue: 's3://avillach-73-bdcatalyst-etl/sstr/',
               description: 'Base S3 path for SSTR input files. Each study\'s input is derived as {INPUT_BASE}/{study_id}_sstr.tsv unless INPUT overrides it.')
        string(name: 'BATCH_SIZE', defaultValue: '1000',
               description: 'Rows per batch insert, passed to every permanent job')
        string(name: 'SSTR_JOB', defaultValue: 'sstr-populate-rds-participants',
               description: 'Jenkins job that runs etl-runners/sstr-populate-rds-participants/Jenkinsfile')
        booleanParam(name: 'RUN_INTEGRATION_TESTS', defaultValue: true,
               description: 'Run the Testcontainers *IT suites (needs a Docker daemon on the agent). These are the only checks that assert real DB state.')
        booleanParam(name: 'CONTINUE_ON_STUDY_FAILURE', defaultValue: true,
               description: 'Keep loading the remaining studies when one fails, then fail the build with a summary. Safe: each study is loaded in its own transaction, scoped to its own study_id.')
        booleanParam(name: 'PREFLIGHT_ONLY', defaultValue: false,
               description: 'Validate every study\'s inputs and stop, without provisioning anything')
        string(name: 'ALL_CONCEPTS_JOB', defaultValue: 'generate-global-all-concepts',
               description: 'Jenkins job that runs the generate-global-all-concepts runner')
        string(name: 'ALL_CONCEPTS_OUTPUT', defaultValue: 's3://avillach-etl/output/',
               description: 'Output location for global_AllConcepts.csv (local path or s3:// URI)')
        string(name: 'VCF_INDEXES_JOB', defaultValue: 'create-vcf-indexes',
               description: 'Jenkins job that runs the create-vcf-indexes runner')
        string(name: 'VCF_INDEXES_OUTPUT', defaultValue: 's3://avillach-etl/output/vcf-indexes/',
               description: 'Output location for vcfIndex.tsv and SampleIds.csv (local path or s3:// URI)')
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

        stage('Resolve studies') {
            steps {
                script {
                    // Read managed inputs: the same CSV the ETL jobs use internally.
                    // Columns: "Study Abbreviated Name", "Study Identifier",
                    //          "Data is ready to process"
                    def managedInputsUri = params.MANAGED_INPUTS?.trim()
                    def managedInputsArgs = managedInputsUri
                        ? "--managed-inputs=${managedInputsUri}"
                        : ''

                    // Use the JAR to dump managed inputs as JSON so we parse exactly
                    // what the job would parse. Fall back to reading the CSV directly
                    // if that helper is not available.
                    def studies = []

                    if (managedInputsUri) {
                        // Read managed inputs CSV directly via the pipeline.
                        // Use an env var to avoid Groovy GString injection into the shell.
                        env.MI_URI = managedInputsUri
                        def lines = sh(returnStdout: true, script: '''
                            aws s3 cp "$MI_URI" - 2>/dev/null || cat "$MI_URI" 2>/dev/null || echo ''
                        ''').trim()

                        if (!lines) {
                            error("Could not read managed inputs from ${managedInputsUri}")
                        }

                        def header = null
                        lines.readLines().each { line ->
                            if (!line.trim()) return
                            def cols = parseCsvLine(line)
                            if (header == null) {
                                header = cols
                                return
                            }
                            def studyId = cols.size() > header.indexOf('Study Identifier') && header.indexOf('Study Identifier') >= 0
                                ? cols[header.indexOf('Study Identifier')].trim() : ''
                            def abv = cols.size() > header.indexOf('Study Abbreviated Name') && header.indexOf('Study Abbreviated Name') >= 0
                                ? cols[header.indexOf('Study Abbreviated Name')].trim() : ''
                            def readyRaw = cols.size() > header.indexOf('Data is ready to process') && header.indexOf('Data is ready to process') >= 0
                                ? cols[header.indexOf('Data is ready to process')].trim() : ''
                            def processedRaw = cols.size() > header.indexOf('Data Processed') && header.indexOf('Data Processed') >= 0
                                ? cols[header.indexOf('Data Processed')].trim() : ''
                            if (studyId) {
                                def ready = parseYesNo(readyRaw, 'Data is ready to process', studyId)
                                def processed = parseYesNo(processedRaw, 'Data Processed', studyId)
                                studies << [studyId: studyId, abv: abv,
                                            ready: ready, processed: processed]
                            }
                        }
                    } else {
                        // No explicit URI: the JAR will use etl.managed-inputs.uri from config.
                        // We need the study list here for the pipeline loop, so require the param.
                        error('MANAGED_INPUTS is required in sweep mode so the pipeline can resolve the study list. ' +
                              'Set it to the s3:// or local path of the managed inputs CSV, or use STUDY_ID for a single study.')
                    }

                    def inputBase = params.INPUT_BASE?.trim()?.replaceAll(/\/$/, '') ?: ''

                    def selected
                    if (params.STUDY_ID?.trim()) {
                        def sid = params.STUDY_ID.trim()
                        def inputUri = params.INPUT?.trim()
                            ?: (inputBase ? "${inputBase}/${sid}_sstr.tsv" : '')
                        if (!inputUri) {
                            error("STUDY_ID=${sid} requires either INPUT or INPUT_BASE to derive the SSTR input URI.")
                        }
                        selected = [[studyId: sid, abv: '', input: inputUri]]
                    } else {
                        selected = studies.findAll { it.ready }
                        if (selected.isEmpty()) {
                            error('No studies are marked ready in managed inputs. ' +
                                  'Run one study manually with STUDY_ID + INPUT, or mark studies ready in the managed inputs CSV.')
                        }
                        // Derive input URIs from INPUT_BASE
                        if (!inputBase) {
                            error('INPUT_BASE is required in sweep mode to derive SSTR input URIs per study.')
                        }
                        selected = selected.collect { s ->
                            s + [input: "${inputBase}/${s.studyId}_sstr.tsv"]
                        }
                    }

                    def bad = selected.findAll { !(it.studyId ==~ /^phs\d{6}$/) }
                    if (bad) {
                        error("Invalid study id(s) — must match phs###### (exactly 6 digits): ${bad*.studyId.join(', ')}")
                    }

                    def dupes = selected*.studyId.countBy { it }
                                        .findAll { k, v -> v > 1 }
                                        .keySet()
                                        .toList()
                    if (dupes) {
                        error("Duplicate study id(s) in managed inputs: ${dupes.join(', ')}")
                    }

                    // SSTR load and VCF indexes only run for studies not yet processed.
                    // Global AllConcepts runs on all ready studies (handled inside the job).
                    UNPROCESSED_STUDIES = selected.findAll { !it.processed }
                    def alreadyProcessed = selected.findAll { it.processed }

                    currentBuild.displayName = params.STUDY_ID?.trim()
                        ? "#${env.BUILD_NUMBER} ${params.STUDY_ID}"
                        : "#${env.BUILD_NUMBER} sweep (${selected.size()} ready, ${UNPROCESSED_STUDIES.size()} unprocessed)"

                    echo "Studies ready (${selected.size()}):"
                    selected.each { s ->
                        echo "  ${s.studyId}  ${s.processed ? '[processed]' : '[new]'}  <- ${s.input}"
                    }
                    if (alreadyProcessed) {
                        echo "${alreadyProcessed.size()} study/studies already processed — skipping DB population and VCF index creation"
                    }
                    if (UNPROCESSED_STUDIES.isEmpty()) {
                        echo 'No unprocessed studies to load. Global AllConcepts will still regenerate.'
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Permanent job stages. One per PERMANENT job, in the order they must run.
        // ---------------------------------------------------------------------

        stage('Load SSTR participants') {
            when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
            steps {
                script {
                    def results = []

                    for (study in UNPROCESSED_STUDIES) {
                        echo "--- ${study.studyId} ---"
                        def outcome
                        try {
                            def downstream = build(
                                job: params.SSTR_JOB,
                                wait: true,
                                propagate: false,
                                parameters: [
                                    string(name: 'STUDY_ID', value: study.studyId),
                                    string(name: 'INPUT',    value: study.input),
                                    string(name: 'BATCH_SIZE', value: params.BATCH_SIZE),
                                    string(name: 'RUN_ID', value: "${env.BUILD_TAG}-${study.studyId}"),
                                    booleanParam(name: 'SKIP_TESTS', value: true),
                                    booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                                ])

                            outcome = [studyId: study.studyId, result: downstream.result,
                                       build: downstream.number, url: downstream.absoluteUrl]

                            try {
                                copyArtifacts(
                                    projectName: params.SSTR_JOB,
                                    selector: specific("${downstream.number}"),
                                    target: "downstream-artifacts/sstr/${study.studyId}",
                                    optional: true)
                            } catch (err) {
                                echo "Could not copy artifacts for ${study.studyId} (${err.message}); " +
                                     "they remain on ${params.SSTR_JOB} #${downstream.number}."
                            }
                        } catch (err) {
                            outcome = [studyId: study.studyId, result: 'NOT_BUILT',
                                       build: null, url: null, error: err.message]
                            echo "${study.studyId}: could not run — ${err.message}"
                        }

                        results << outcome
                        echo "${study.studyId}: ${outcome.result}"

                        if (outcome.result != 'SUCCESS' && !params.CONTINUE_ON_STUDY_FAILURE) {
                            echo 'CONTINUE_ON_STUDY_FAILURE is off — stopping the sweep here.'
                            break
                        }
                    }

                    // --- summary --------------------------------------------------
                    def ok       = results.findAll { it.result == 'SUCCESS' }
                    def warned   = results.findAll { it.result == 'UNSTABLE' }
                    def failed   = results.findAll { !(it.result in ['SUCCESS', 'UNSTABLE']) }
                    def skipped  = UNPROCESSED_STUDIES.size() - results.size()

                    echo ''
                    echo '================ SSTR load summary ================'
                    results.each { r -> echo String.format('  %-12s %-10s %s', r.studyId, r.result, r.url ?: '') }
                    if (skipped > 0) {
                        echo "  ${skipped} study/studies not attempted"
                    }
                    echo "  ${ok.size()} succeeded, ${warned.size()} with warnings, " +
                         "${failed.size()} failed, ${skipped} not attempted"
                    echo '=================================================='

                    if (failed) {
                        error("SSTR load failed for: ${failed*.studyId.join(', ')}. " +
                              'Each study is loaded in its own transaction, so a failed study left RDS unchanged — ' +
                              'fix its input and re-run just that study with STUDY_ID.')
                    }
                    if (warned) {
                        unstable("Loaded with warnings: ${warned*.studyId.join(', ')}")
                    }
                }
            }
        }

        stage('Generate global AllConcepts') {
            steps {
                script {
                    echo 'Generating global_AllConcepts.csv from populated database...'

                    def conceptParams = [
                        string(name: 'OUTPUT', value: params.ALL_CONCEPTS_OUTPUT),
                        string(name: 'RUN_ID', value: "${env.BUILD_TAG}-all-concepts"),
                        booleanParam(name: 'SKIP_TESTS', value: true),
                    ]
                    if (params.MANAGED_INPUTS?.trim()) {
                        conceptParams << string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS)
                    }

                    def downstream = build(
                        job: params.ALL_CONCEPTS_JOB,
                        wait: true,
                        propagate: true,
                        parameters: conceptParams)

                    try {
                        copyArtifacts(
                            projectName: params.ALL_CONCEPTS_JOB,
                            selector: specific("${downstream.number}"),
                            target: 'downstream-artifacts/all-concepts',
                            optional: true)
                    } catch (err) {
                        echo "Could not copy artifacts for all-concepts (${err.message}); " +
                             "they remain on ${params.ALL_CONCEPTS_JOB} #${downstream.number}."
                    }
                }
            }
        }

        stage('Create VCF indexes') {
            when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
            steps {
                script {
                    echo 'Creating VCF indexes from genomic data...'

                    def vcfParams = [
                        string(name: 'OUTPUT', value: params.VCF_INDEXES_OUTPUT),
                        string(name: 'RUN_ID', value: "${env.BUILD_TAG}-vcf-indexes"),
                        booleanParam(name: 'SKIP_TESTS', value: true),
                    ]

                    def downstream = build(
                        job: params.VCF_INDEXES_JOB,
                        wait: true,
                        propagate: true,
                        parameters: vcfParams)

                    if (downstream.result == 'UNSTABLE') {
                        unstable('Create VCF indexes completed with warnings')
                    }

                    try {
                        copyArtifacts(
                            projectName: params.VCF_INDEXES_JOB,
                            selector: specific("${downstream.number}"),
                            target: 'downstream-artifacts/vcf-indexes',
                            optional: true)
                    } catch (err) {
                        echo "Could not copy artifacts for vcf-indexes (${err.message}); " +
                             "they remain on ${params.VCF_INDEXES_JOB} #${downstream.number}."
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'downstream-artifacts/**', allowEmptyArchive: true
        }
        failure {
            echo "Permanent ETL pipeline FAILED at stage '${env.STAGE_NAME}'. Each study's own build page " +
                 'has its JSON report and runner log; the exit code there says whether this was ' +
                 'validation (2), data (3, study rolled back), infrastructure (4, retryable), or config (5).'
        }
        unstable {
            echo 'Permanent ETL pipeline completed WITH WARNINGS. Typically 0 new participants (a reload) ' +
                 'or 0 sample rows — confirm that is expected for the studies listed above.'
        }
        success {
            echo 'Permanent ETL pipeline completed. Every study loaded and passed its output validation.'
        }
    }
}

@NonCPS
static boolean parseYesNo(String value, String column, String studyId) {
    if (!value) return false
    def v = value.trim().toLowerCase()
    if (v == 'yes') return true
    if (v == 'no' || v == '') return false
    throw new IllegalArgumentException("Study ${studyId}: invalid value '${value}' in column '${column}'; expected 'Yes' or 'No'")
}

@NonCPS
static List<String> parseCsvLine(String line) {
    def fields = []
    def current = new StringBuilder()
    boolean inQuotes = false
    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i)
        if (c == (char)'"') {
            inQuotes = !inQuotes
        } else if (c == (char)',' && !inQuotes) {
            fields << current.toString().trim()
            current = new StringBuilder()
        } else {
            current.append(c)
        }
    }
    fields << current.toString().trim()
    return fields
}
