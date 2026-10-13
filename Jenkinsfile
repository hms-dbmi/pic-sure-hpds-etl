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
// The participant database is a long-lived PostgreSQL 16 container on the Jenkins host.
// 'Start participant DB' makes sure it is up (see OVERWRITE_DB, DB_RESTORE_FROM and
// IF_LAST_RUN_FAILED) before the first DB-backed stage, and post { always } backs it up to S3 --
// as the latest clean backup only when this build succeeded. It is never torn down. See
// docs/PARTICIPANT_DB.md.
//
// Build and test run here once, as the gate for the whole run. Downstream jobs are invoked with
// SKIP_TESTS=true so the same commit's suites are not re-run per study. The per-study jobs (SSTR
// load and allConcepts generation) rebuild nothing: 'Package runner image' builds their runner
// image once and passes it as PREBUILT_IMAGE_TAR. With PARALLEL_STUDY_LOADS, at most
// STUDY_CONCURRENCY of them run at a time.
//
// Per-study allConcepts: each unprocessed study's decoded data is turned into per-consent
// allConcepts files under {DATA_ROOT}/{study_id}/allConcepts/ (overwritten in place; the bucket is
// versioned), which Merge AllConcepts then reads. A generation failure halts the pipeline
// before the merge, so a merged file never mixes this run's output with a missing study.
//
// Trigger modes (both supported by this pipeline):
//   STUDY_ID blank    sweep every study marked "Data is ready to process" = Yes in managed inputs
//   STUDY_ID set      load exactly that study (the reload/manual entry path); INPUT overrides
//                     the SSTR file discovered under DATA_ROOT
//
// Exit codes the stages gate on (see ExitCode.java):
//   0 success | 1 unknown | 2 validation | 3 data | 4 infrastructure | 5 config
// =============================================================================

pipeline {
    agent any

    tools {
        jdk 'jdk-25'
    }

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
               description: 'Only used with STUDY_ID: the SSTR input URI for that study, overriding discovery under DATA_ROOT.')
        string(name: 'MANAGED_INPUTS', defaultValue: '',
               description: 'REQUIRED. The managed inputs CSV URI. The study list is resolved from it here, and it is passed to the global AllConcepts and VCF jobs.')
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
               description: 'Run the per-study stages (SSTR load, per-study allConcepts) concurrently, one runner each, at most STUDY_CONCURRENCY at a time. Correct (see Concurrency in docs/JENKINS.md) but off by default: studies sharing subjects serialize on those rows anyway, and sequential keeps the participant DB load predictable and the log readable.')
        string(name: 'STUDY_CONCURRENCY', defaultValue: '5',
               description: 'With PARALLEL_STUDY_LOADS: most per-study builds (and EC2 runners) at once. Every study still runs; the rest wait for a free slot. Ignored when sequential.')
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
        string(name: 'MERGE_ALLCONCEPTS_JOB', defaultValue: 'merge-allconcepts',
               description: 'Jenkins job that runs the merge-allconcepts runner')
        string(name: 'ALLCONCEPTS_MANIFEST_JOB', defaultValue: 'generate-allconcepts-manifest',
               description: 'Jenkins job that writes the versioned allConcepts_manifest.csv at DATA_ROOT (last stage)')
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

        stage('Build') {
            steps {
                sh './mvnw -B clean package -DskipTests'
            }
        }

        stage('Tests') {
            steps {
                // Jenkins runs in a podman container that reaches the HOST's podman through the
                // mounted /var/run/docker.sock. Ryuk (Testcontainers' cleanup container) would bind
                // that path from the host, where it does not exist, and never start; and the test
                // containers publish their ports on the host, which is not this container's
                // localhost. Containers are still removed when the JVM exits normally.
                withEnv(['TESTCONTAINERS_RYUK_DISABLED=true',
                         'TESTCONTAINERS_HOST_OVERRIDE=host.containers.internal']) {
                    sh(params.RUN_INTEGRATION_TESTS ? './mvnw -B verify' : './mvnw -B test')
                }
                // The participant database scripts (stubbed docker/aws; seconds, no AWS or runtime needed).
                sh 'make -C etl-runners/participant-db test'
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
                    // Catch an unusable output before anything is provisioned: a local path
                    // resolves inside the runner container and is destroyed with the instance.
                    ['ALL_CONCEPTS_OUTPUT', 'VCF_INDEXES_OUTPUT', 'DATA_ROOT'].each { p ->
                        if (!params[p]?.trim()?.startsWith('s3://')) {
                            error("${p} must be an s3:// URI, got '${params[p]}'.")
                        }
                    }
                    studyConcurrency()   // fails now on a bad value, not after the DB is up

                    // Read managed inputs: the same CSV the ETL jobs use internally.
                    // Columns: "Study Abbreviated Name", "Study Identifier",
                    //          "Data is ready to process", "Data Processed"
                    def managedInputsUri = params.MANAGED_INPUTS?.trim()
                    if (!managedInputsUri) {
                        error('MANAGED_INPUTS is required: the study list comes from it, and the global AllConcepts ' +
                              'and VCF jobs read it.')
                    }

                    // Use an env var to avoid Groovy GString injection into the shell.
                    env.MI_URI = managedInputsUri
                    def lines = sh(returnStdout: true, script: '''
                        aws s3 cp "$MI_URI" - 2>/dev/null || cat "$MI_URI" 2>/dev/null || echo ''
                    ''').trim()
                    if (!lines) {
                        error("Could not read managed inputs from ${managedInputsUri}")
                    }

                    def studies = []
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

                    def selected
                    if (params.STUDY_ID?.trim()) {
                        def sid = params.STUDY_ID.trim()
                        def row = studies.find { it.studyId == sid }
                        // An explicit reload: the ready/processed flags do not apply.
                        selected = [[studyId: sid, abv: row?.abv ?: '', processed: false,
                                     input: params.INPUT?.trim() ?: '']]
                    } else {
                        selected = studies.findAll { it.ready }
                        if (selected.isEmpty()) {
                            error('No studies are marked ready in managed inputs. ' +
                                  'Run one study manually with STUDY_ID, or mark studies ready in the managed inputs CSV.')
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

                    // Discover each unprocessed study's SSTR the way participants-migration does:
                    // staged files keep NHLBI's own name (sstr_phs######.v#.txt), so it cannot be
                    // derived from the study id alone. Sequential on purpose: the env-var handoff
                    // into sh is not safe under `parallel`.
                    def dataRoot = params.DATA_ROOT.trim().replaceAll(/\/+$/, '')
                    def missing = []
                    for (s in UNPROCESSED_STUDIES) {
                        def studyBase = "${dataRoot}/${s.studyId}"

                        if (!s.input) {
                            env.RAW_DIR = "${studyBase}/rawData/"
                            def listing = sh(returnStdout: true, script: 'aws s3 ls "$RAW_DIR" 2>/dev/null || true')
                            def file = pickSstr(listing, s.studyId)
                            if (file) {
                                s.input = "${env.RAW_DIR}${file}"
                            } else {
                                missing << "${s.studyId} (no sstr_${s.studyId}.*.txt in ${env.RAW_DIR})"
                            }
                        }

                        // The generator's inputs, checked here so a missing one fails the build
                        // before anything is provisioned rather than after the SSTR loads.
                        s.dataDir = "${studyBase}/${params.DECODED_DATA_DIR.trim().replaceAll(/^\/+|\/+$/, '')}/"
                        s.mapping = "${studyBase}/${params.CONCEPT_MAPPING_FILE.trim().replaceAll(/^\/+/, '')}"
                        env.DATA_DIR = s.dataDir
                        env.MAPPING_URI = s.mapping
                        def csvs = sh(returnStdout: true,
                                      script: 'aws s3 ls "$DATA_DIR" 2>/dev/null | grep -ci "\\.csv$" || true').trim()
                        if (!(csvs.isInteger() && csvs.toInteger() > 0)) {
                            missing << "${s.studyId} (no decoded data CSVs in ${s.dataDir})"
                        }
                        if (sh(returnStatus: true, script: 'r="${MAPPING_URI#s3://}"; ' +
                               'aws s3api head-object --bucket "${r%%/*}" --key "${r#*/}" >/dev/null 2>&1') != 0) {
                            missing << "${s.studyId} (no concept mapping at ${s.mapping})"
                        }
                    }
                    if (missing) {
                        error("Missing inputs for: ${missing.join('; ')}. Stage the files (or pass INPUT with STUDY_ID " +
                              'for the SSTR), or mark the study not ready.')
                    }

                    currentBuild.displayName = params.STUDY_ID?.trim()
                        ? "#${env.BUILD_NUMBER} ${params.STUDY_ID}"
                        : "#${env.BUILD_NUMBER} sweep (${selected.size()} ready, ${UNPROCESSED_STUDIES.size()} unprocessed)"

                    echo "Studies ready (${selected.size()}):"
                    selected.each { s ->
                        echo "  ${s.studyId}  ${s.processed ? '[processed]' : '[new]'}  ${s.input ? '<- ' + s.input : ''}"
                        if (s.dataDir) {
                            echo "      decoded data ${s.dataDir}, mapping ${s.mapping}"
                        }
                    }
                    if (alreadyProcessed) {
                        echo "${alreadyProcessed.size()} study/studies already processed — skipping DB population, VCF index creation, and per-study allConcepts"
                    }
                    if (UNPROCESSED_STUDIES.isEmpty()) {
                        echo 'No unprocessed studies to load. Global AllConcepts will still regenerate.'
                    }
                }
            }
        }

        stage('Start participant DB') {
            when { expression { !params.PREFLIGHT_ONLY } }
            steps {
                script {
                    def downstream = build(
                        job: params.DB_START_JOB,
                        wait: true,
                        propagate: false,
                        parameters: [
                            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-db"),
                            booleanParam(name: 'OVERWRITE', value: params.OVERWRITE_DB),
                            string(name: 'RESTORE_FROM', value: params.DB_RESTORE_FROM?.trim() ?: ''),
                            string(name: 'IF_LAST_RUN_FAILED', value: params.IF_LAST_RUN_FAILED),
                            string(name: 'ENV',    value: params.ENV),
                        ])
                    if (downstream.result != 'SUCCESS') {
                        error("${params.DB_START_JOB} #${downstream.number} ended ${downstream.result}: the participant " +
                              'database did not come up -- see that job\'s console.')
                    }
                    // Only now does post { always } own the end-of-run backup. A failed start changed
                    // nothing worth saving.
                    env.DB_STARTED = 'true'
                }
            }
        }

        // One runner image for every per-study build. Each would otherwise compile the JAR and
        // build, save and upload its own copy of the same image on this host -- hundreds of times
        // over in a large sweep.
        stage('Package runner image') {
            when { expression { !params.PREFLIGHT_ONLY && !UNPROCESSED_STUDIES.isEmpty() } }
            steps {
                script {
                    // Set first, so a partial upload is still cleaned up in post.
                    env.RUNNER_IMAGE_TAR = "hpds-etl-runner-${env.BUILD_TAG.replaceAll(/[^A-Za-z0-9._-]/, '-')}.tar.gz"
                    sh "make -C etl-runners/sstr-populate-rds-participants package IMAGE_TAR=${env.RUNNER_IMAGE_TAR}"
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
                    def results = java.util.Collections.synchronizedList([])

                    def loadStudy = { s ->
                        echo "--- ${s.studyId} ---"
                        def outcome
                        try {
                            def downstream = build(
                                job: params.SSTR_JOB,
                                wait: true,
                                propagate: false,
                                parameters: [
                                    string(name: 'STUDY_ID', value: s.studyId),
                                    string(name: 'INPUT',    value: s.input),
                                    string(name: 'BATCH_SIZE', value: params.BATCH_SIZE),
                                    string(name: 'RUN_ID', value: "${env.BUILD_TAG}-${s.studyId}"),
                                    string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                    string(name: 'ENV',  value: params.ENV),
                                    booleanParam(name: 'SKIP_TESTS', value: true),
                                    booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                                ])

                            outcome = [studyId: s.studyId, result: downstream.result,
                                       build: downstream.number, url: downstream.absoluteUrl]

                            try {
                                copyArtifacts(
                                    projectName: params.SSTR_JOB,
                                    selector: specific("${downstream.number}"),
                                    target: "downstream-artifacts/sstr/${s.studyId}",
                                    optional: true)
                            } catch (err) {
                                echo "Could not copy artifacts for ${s.studyId} (${err.message}); " +
                                     "they remain on ${params.SSTR_JOB} #${downstream.number}."
                            }
                        } catch (err) {
                            outcome = [studyId: s.studyId, result: 'NOT_BUILT',
                                       build: null, url: null, error: err.message]
                            echo "${s.studyId}: could not run — ${err.message}"
                        }

                        results << outcome
                        echo "${s.studyId}: ${outcome.result}"
                        return outcome.result in ['SUCCESS', 'UNSTABLE']
                    }

                    runPerStudy(UNPROCESSED_STUDIES, loadStudy)

                    // --- summary --------------------------------------------------
                    def ok       = results.findAll { it.result == 'SUCCESS' }
                    def warned   = results.findAll { it.result == 'UNSTABLE' }
                    def failed   = results.findAll { !(it.result in ['SUCCESS', 'UNSTABLE']) }
                    def skipped  = UNPROCESSED_STUDIES.size() - results.size()

                    echo ''
                    echo '================ SSTR load summary ================'
                    results.each { r -> echo String.format('  %-12s %-10s %s', r.studyId, r.result, r.url ?: '') }
                    echo "  ${ok.size()} succeeded, ${warned.size()} with warnings, " +
                         "${failed.size()} failed" + (skipped ? ", ${skipped} not attempted" : '')
                    echo '=================================================='

                    if (failed) {
                        error("SSTR load failed for: ${failed*.studyId.join(', ')}. " +
                              'Each study is loaded in its own transaction, so a failed study left the participant DB unchanged — ' +
                              'fix its input and re-run just that study with STUDY_ID.')
                    }
                    if (warned) {
                        unstable("Loaded with warnings: ${warned*.studyId.join(', ')}")
                    }
                }
            }
        }

        stage('Generate global AllConcepts') {
            // No pre-flight mode of its own: it reads the participant DB, which PREFLIGHT_ONLY
            // does not start.
            when { expression { !params.PREFLIGHT_ONLY } }
            steps {
                script {
                    echo 'Generating global_AllConcepts.csv from populated database...'

                    def downstream = build(
                        job: params.ALL_CONCEPTS_JOB,
                        wait: true,
                        propagate: false,
                        parameters: [
                            string(name: 'OUTPUT', value: params.ALL_CONCEPTS_OUTPUT),
                            string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS),
                            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-all-concepts"),
                            string(name: 'ENV',    value: params.ENV),
                            booleanParam(name: 'SKIP_TESTS', value: true),
                        ])

                    if (downstream.result == 'UNSTABLE') {
                        unstable("${params.ALL_CONCEPTS_JOB} #${downstream.number} completed with warnings")
                    } else if (downstream.result != 'SUCCESS') {
                        error("${params.ALL_CONCEPTS_JOB} #${downstream.number} ended ${downstream.result}")
                    }

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

                    // propagate must be false: build() throws for ANY downstream result worse
                    // than SUCCESS, UNSTABLE included, which would halt the pipeline on warnings.
                    def downstream = build(
                        job: params.VCF_INDEXES_JOB,
                        wait: true,
                        propagate: false,
                        parameters: [
                            string(name: 'OUTPUT', value: params.VCF_INDEXES_OUTPUT),
                            string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS),
                            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-vcf-indexes"),
                            string(name: 'ENV',    value: params.ENV),
                            booleanParam(name: 'SKIP_TESTS', value: true),
                            booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                        ])

                    if (downstream.result == 'UNSTABLE') {
                        unstable('Create VCF indexes completed with warnings')
                    } else if (downstream.result != 'SUCCESS') {
                        error("${params.VCF_INDEXES_JOB} #${downstream.number} ended ${downstream.result}")
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

        stage('Generate per-study AllConcepts') {
            when { expression { !UNPROCESSED_STUDIES.isEmpty() } }
            steps {
                script {
                    def results = java.util.Collections.synchronizedList([])

                    def generateStudy = { s ->
                        echo "--- ${s.studyId} ---"
                        def outcome
                        try {
                            def downstream = build(
                                job: params.ALL_CONCEPTS_GENERATOR_JOB,
                                wait: true,
                                propagate: false,
                                parameters: [
                                    string(name: 'STUDY_ID', value: s.studyId),
                                    string(name: 'DATA_DIR', value: s.dataDir),
                                    string(name: 'MAPPING',  value: s.mapping),
                                    string(name: 'OUTPUT',   value: params.DATA_ROOT.trim()),
                                    booleanParam(name: 'SKIP_ANALYSIS', value: params.SKIP_ANALYSIS),
                                    string(name: 'RUN_ID', value: "${env.BUILD_TAG}-allconcepts-${s.studyId}"),
                                    string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
                                    string(name: 'ENV',    value: params.ENV),
                                    booleanParam(name: 'SKIP_TESTS', value: true),
                                    booleanParam(name: 'PREFLIGHT_ONLY', value: params.PREFLIGHT_ONLY),
                                ])

                            outcome = [studyId: s.studyId, result: downstream.result,
                                       build: downstream.number, url: downstream.absoluteUrl]

                            try {
                                copyArtifacts(
                                    projectName: params.ALL_CONCEPTS_GENERATOR_JOB,
                                    selector: specific("${downstream.number}"),
                                    target: "downstream-artifacts/per-study-all-concepts/${s.studyId}",
                                    optional: true)
                            } catch (err) {
                                echo "Could not copy artifacts for ${s.studyId} (${err.message}); " +
                                     "they remain on ${params.ALL_CONCEPTS_GENERATOR_JOB} #${downstream.number}."
                            }
                        } catch (err) {
                            outcome = [studyId: s.studyId, result: 'NOT_BUILT',
                                       build: null, url: null, error: err.message]
                            echo "${s.studyId}: could not run — ${err.message}"
                        }

                        results << outcome
                        echo "${s.studyId}: ${outcome.result}"
                        return outcome.result in ['SUCCESS', 'UNSTABLE']
                    }

                    runPerStudy(UNPROCESSED_STUDIES, generateStudy)

                    // --- summary --------------------------------------------------
                    def ok       = results.findAll { it.result == 'SUCCESS' }
                    def warned   = results.findAll { it.result == 'UNSTABLE' }
                    def failed   = results.findAll { !(it.result in ['SUCCESS', 'UNSTABLE']) }
                    def skipped  = UNPROCESSED_STUDIES.size() - results.size()

                    echo ''
                    echo '============ Per-study allConcepts summary ============'
                    results.each { r -> echo String.format('  %-12s %-10s %s', r.studyId, r.result, r.url ?: '') }
                    echo "  ${ok.size()} succeeded, ${warned.size()} with warnings, " +
                         "${failed.size()} failed" + (skipped ? ", ${skipped} not attempted" : '')
                    echo '======================================================='

                    // Halts here, before Merge AllConcepts: a merge run with a study missing or
                    // half-written would publish merged files that silently lack its rows.
                    if (failed) {
                        error("Per-study allConcepts failed for: ${failed*.studyId.join(', ')}. Merge AllConcepts " +
                              'was not run. Fix the study and re-run it with STUDY_ID (its files are overwritten in place).')
                    }
                    if (warned) {
                        unstable("Per-study allConcepts completed with warnings: ${warned*.studyId.join(', ')}")
                    }
                }
            }
        }

        stage('Merge AllConcepts') {
            // No pre-flight mode of its own: skipped rather than provisioned under PREFLIGHT_ONLY.
            when { expression { !params.PREFLIGHT_ONLY } }
            steps {
                script {
                    echo 'Merging per-consent allConcepts files where needed...'

                    // A STUDY_ID reload loads that study whatever its ready flag, so merge exactly it;
                    // a sweep merges every ready study. (The manifest still lists only ready studies.)
                    def mergeStudyIds = params.STUDY_ID?.trim() ?: ''
                    def downstream = build(
                        job: params.MERGE_ALLCONCEPTS_JOB,
                        wait: true,
                        propagate: false,
                        parameters: [
                            string(name: 'INPUT', value: params.DATA_ROOT.trim()),
                            string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS.trim()),
                            string(name: 'STUDY_IDS', value: mergeStudyIds),
                            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-merge-allconcepts"),
                            string(name: 'ENV',    value: params.ENV),
                            booleanParam(name: 'SKIP_TESTS', value: true),
                        ])

                    if (downstream.result == 'UNSTABLE') {
                        unstable("${params.MERGE_ALLCONCEPTS_JOB} #${downstream.number} completed with warnings")
                    } else if (downstream.result != 'SUCCESS') {
                        error("${params.MERGE_ALLCONCEPTS_JOB} #${downstream.number} ended ${downstream.result}")
                    }

                    try {
                        copyArtifacts(
                            projectName: params.MERGE_ALLCONCEPTS_JOB,
                            selector: specific("${downstream.number}"),
                            target: 'downstream-artifacts/merge-allconcepts',
                            optional: true)
                    } catch (err) {
                        echo "Could not copy artifacts for merge-allconcepts (${err.message}); " +
                             "they remain on ${params.MERGE_ALLCONCEPTS_JOB} #${downstream.number}."
                    }
                }
            }
        }

        stage('AllConcepts manifest') {
            // Last stage: verifies the global AllConcepts file (ALL_CONCEPTS_OUTPUT) and every ready
            // consent group's MERGED file, and publishes the versioned
            // {DATA_ROOT}/allConcepts_manifest.csv for production ingestion. Writes nothing (and fails
            // the build) if any of them is missing or stale, so ingestion never gets a bad manifest.
            when { expression { !params.PREFLIGHT_ONLY } }
            steps {
                script {
                    def downstream = build(
                        job: params.ALLCONCEPTS_MANIFEST_JOB,
                        wait: true,
                        propagate: false,
                        parameters: [
                            string(name: 'INPUT', value: params.DATA_ROOT.trim()),
                            string(name: 'GLOBAL_ALLCONCEPTS', value: params.ALL_CONCEPTS_OUTPUT.trim()),
                            string(name: 'MANAGED_INPUTS', value: params.MANAGED_INPUTS?.trim() ?: ''),
                            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-allconcepts-manifest"),
                            string(name: 'ENV',    value: params.ENV),
                            booleanParam(name: 'SKIP_TESTS', value: true),
                        ])

                    try {
                        copyArtifacts(
                            projectName: params.ALLCONCEPTS_MANIFEST_JOB,
                            selector: specific("${downstream.number}"),
                            target: 'downstream-artifacts/allconcepts-manifest',
                            optional: true)
                    } catch (err) {
                        echo "Could not copy artifacts for allconcepts-manifest (${err.message}); " +
                             "they remain on ${params.ALLCONCEPTS_MANIFEST_JOB} #${downstream.number}."
                    }

                    if (downstream.result == 'UNSTABLE') {
                        unstable("${params.ALLCONCEPTS_MANIFEST_JOB} #${downstream.number} completed with warnings")
                    } else if (downstream.result != 'SUCCESS') {
                        error("${params.ALLCONCEPTS_MANIFEST_JOB} #${downstream.number} ended ${downstream.result}: " +
                              'the manifest was NOT updated. Its report lists every missing or stale MERGED file.')
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                backupParticipantDb(params.DB_BACKUP_JOB, params.ENV)
                removeRunnerImage()
            }
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

// Dumps the participant DB to S3 at the end of the run, if this build's start succeeded. The
// database stays up. A SUCCESS or UNSTABLE build overwrites participant_db.tar, the latest clean
// backup; a failed or aborted one writes participant_db_not_promoted.tar instead and marks the live
// database as holding a failed run (the next start acts on IF_LAST_RUN_FAILED). A failed backup
// fails the build loudly: this run's writes then exist only in the live database.
def backupParticipantDb(String backupJob, String envName) {
    if (env.DB_STARTED != 'true') {
        return
    }
    boolean promote = currentBuild.currentResult in ['SUCCESS', 'UNSTABLE']
    echo "Backing up the participant database (promote: ${promote})"
    def downstream = build(
        job: backupJob,
        wait: true,
        propagate: false,
        parameters: [
            booleanParam(name: 'PROMOTE_BACKUP', value: promote),
            string(name: 'ENV', value: envName),
        ])
    if (downstream.result != 'SUCCESS') {
        currentBuild.result = 'FAILURE'
        echo "ERROR: ${backupJob} #${downstream.number} ended ${downstream.result}: this run's writes are in the " +
             "live participant database but in no verified backup. Re-run ${backupJob} " +
             "(PROMOTE_BACKUP=${promote}) before the next pipeline run."
    }
}

// The configured STUDY_CONCURRENCY, or 1 when per-study stages run sequentially.
def studyConcurrency() {
    if (!params.PARALLEL_STUDY_LOADS) {
        return 1
    }
    int n = 0
    try {
        n = params.STUDY_CONCURRENCY.trim() as int
    } catch (ignored) { }
    if (n < 1) {
        error("STUDY_CONCURRENCY must be a positive whole number, got '${params.STUDY_CONCURRENCY}'.")
    }
    return n
}

// Runs body(study) for every study, at most studyConcurrency() at a time. body returns true when
// the study succeeded. A fixed pool of workers each takes the next study until none are left.
// Taking one is safe without a lock: pipeline Groovy runs every parallel branch on one thread and
// only switches between them at a step, and there is no step between reading the cursor and
// advancing it. With CONTINUE_ON_STUDY_FAILURE off, a failure stops new studies from starting;
// builds already running finish, and the caller's summary counts the rest as not attempted.
def runPerStudy(List studies, Closure body) {
    int workers = Math.min(studyConcurrency(), studies.size())
    def cursor = [index: 0, stop: false]
    def worker = {
        while (!cursor.stop && cursor.index < studies.size()) {
            def s = studies[cursor.index]
            cursor.index = cursor.index + 1
            if (!body(s) && !params.CONTINUE_ON_STUDY_FAILURE) {
                echo "CONTINUE_ON_STUDY_FAILURE is off: ${s.studyId} failed, so no further studies are started."
                cursor.stop = true
            }
        }
    }
    if (workers <= 1) {
        worker()
        return
    }
    echo "Running ${studies.size()} study build(s), at most ${workers} at a time"
    def branches = [:]
    for (int w = 1; w <= workers; w++) {
        branches["worker-${w}"] = { worker() }
    }
    parallel branches
}

// Deletes the shared runner image: the tarball in S3, the local one, and the image in this host's
// podman storage. Every per-study build that used it has finished by now.
def removeRunnerImage() {
    if (!env.RUNNER_IMAGE_TAR) {
        return
    }
    sh(returnStatus: true, script: '''
        bucket=$(sed -n 's/^stack_s3_bucket[[:space:]]*=[[:space:]]*"\\(.*\\)"/\\1/p' etl-runners/environments/$ENV.tfvars)
        aws s3 rm "s3://$bucket/etl-runner/container/$RUNNER_IMAGE_TAR" --region "$AWS_REGION" || true
        rm -f "etl-runners/sstr-populate-rds-participants/$RUNNER_IMAGE_TAR"
        docker rmi "${RUNNER_IMAGE_TAR%.tar.gz}" >/dev/null 2>&1 || true
    ''')
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

// Picks a study's SSTR from an `aws s3 ls` listing of its rawData folder, by the same rule as
// ParticipantsMigrationJob.isSstrFileFor / isCanonicalSstrName: a .txt naming the study,
// starting sstr_ or bdc-ingestion-only__sstr_ (case-insensitive), the canonical sstr_{phs}.{v}.txt
// preferred over folder-flattened copies. Null when there is none.
@NonCPS
static String pickSstr(String listing, String studyId) {
    def sid = studyId.toLowerCase()
    def names = listing.readLines()
        .findAll { !it.trim().startsWith('PRE ') && it.trim() }
        .collect { it.trim().split(/\s+/)[-1] }
        .findAll { n ->
            def l = n.toLowerCase()
            l.endsWith('.txt') && l.contains(sid) && (l.startsWith('sstr_') || l.startsWith('bdc-ingestion-only__sstr_'))
        }
    if (!names) return null
    def canonical = { String n -> def l = n.toLowerCase(); l.startsWith('sstr_') && !l.startsWith('sstr__') }
    return names.sort { a, b -> (canonical(a) == canonical(b)) ? a <=> b : (canonical(a) ? -1 : 1) }[0]
}
