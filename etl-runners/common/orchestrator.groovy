// =============================================================================
// Helpers for the permanent ETL orchestrator (/Jenkinsfile), loaded once per build with
//   ORCH = load 'etl-runners/common/orchestrator.groovy'
//
// Why a loaded file: declarative compiles the whole pipeline {} block into one JVM method, which
// has a 64 KB limit ("Method too large"). Stage logic lives here; /Jenkinsfile keeps the DAG.
//
// Conventions:
//   - Every function that runs a step (sh, build, echo, error, ...) is plain CPS code. @NonCPS is
//     only on pure helpers (parsing, sorting) that call no step.
//   - A catch meant for a step's own failure calls rethrowIfInterrupted first, so an abort or a
//     timeout() is never swallowed.
//   - State is passed in and returned, not shared through the Jenkinsfile's binding.
// =============================================================================

// Hard limits that come from the child jobs (their own options { timeout }): a JOB_TIMEOUT_SECONDS
// above these would be cut short by the child's Jenkins timeout before the runner's own monitor
// fires, so the child would never write its timeout report. Each leaves time for provisioning.
// merge-allconcepts: 9 h Jenkins timeout, less 1 h for building, provisioning and teardown. Its
// infrastructure retry is capped to whatever is left of the 9 h, so only the first attempt needs to fit.
MERGE_TIMEOUT_MAX_SECONDS     = 28800
GENERATOR_TIMEOUT_MIN_SECONDS = 7200    // all-concepts-data-generator's own default
GENERATOR_TIMEOUT_MAX_SECONDS = 12600   // all-concepts-data-generator: 4 h Jenkins timeout
STUDY_CONCURRENCY_MAX         = 50

// The managed-inputs columns this pipeline (and ManagedInputsService) reads.
MANAGED_INPUTS_COLUMNS = ['Study Abbreviated Name', 'Study Identifier', 'Data is ready to process', 'Data Processed']

// -----------------------------------------------------------------------------
// Resolve studies
// -----------------------------------------------------------------------------

// Validates the run's parameters, reads managed inputs, picks the studies to load, and probes their
// inputs in S3. Returns [selected: every study in this run, unprocessed: the studies to load (minus
// any excluded), excluded: [[studyId, abv, reason]]].
//
// A sweep EXCLUDES a study whose inputs are missing (no SSTR, no decoded data CSVs, no mapping)
// instead of failing: the build is marked UNSTABLE, the study is listed in the end-of-run report
// with why, and later stages get a managed-inputs copy with it marked not ready
// (laterStageManagedInputs). A single-study run (STUDY_ID) still fails on a missing input, and so
// does a sweep whose every study to load was excluded -- that is a misconfiguration (DATA_ROOT,
// DECODED_DATA_DIR, CONCEPT_MAPPING_FILE), not a data problem.
def resolveStudies() {
    // Catch an unusable location before anything is provisioned: a local path resolves inside the
    // runner container and is destroyed with the instance.
    for (p in ['ALL_CONCEPTS_OUTPUT', 'VCF_INDEXES_OUTPUT', 'DATA_ROOT', 'MANAGED_INPUTS']) {
        if (!"${params[p] ?: ''}".trim().startsWith('s3://')) {
            error("${p} must be an s3:// URI, got '${params[p] ?: ''}'." +
                  (p == 'MANAGED_INPUTS' ? ' The study list comes from it, and every later stage reads it on its runner.' : ''))
        }
    }
    // Fail now on a bad value, not after the DB is up.
    studyConcurrency()
    intParam('MERGE_ALLCONCEPTS_PARALLELISM', 1, 16)
    intParam('MERGE_ALLCONCEPTS_TIMEOUT_SECONDS', 60, MERGE_TIMEOUT_MAX_SECONDS)
    intParam('GENERATOR_TIMEOUT_BASE_SECONDS', 0, GENERATOR_TIMEOUT_MAX_SECONDS)
    intParam('GENERATOR_TIMEOUT_SECONDS_PER_GB', 0, GENERATOR_TIMEOUT_MAX_SECONDS)
    if (!params.MERGE_ALLCONCEPTS_INSTANCE_TYPE?.trim()) {
        error('MERGE_ALLCONCEPTS_INSTANCE_TYPE must be set (e.g. c5.xlarge).')
    }

    def studies = readManagedInputs(params.MANAGED_INPUTS.trim())
    boolean single = params.STUDY_ID?.trim() as boolean

    def selected
    if (single) {
        def sid = params.STUDY_ID.trim()
        def row = studies.find { it.studyId == sid }
        // An explicit reload: the ready/processed flags do not apply.
        selected = [[studyId: sid, abv: row?.abv ?: '', processed: false, input: params.INPUT?.trim() ?: '']]
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
    def dupes = duplicates(selected*.studyId)
    if (dupes) {
        error("Duplicate study id(s) in managed inputs: ${dupes.join(', ')}")
    }

    // SSTR load, VCF indexes and per-study allConcepts only run for studies not yet processed.
    // Global AllConcepts, the merge and the manifest cover every ready study.
    def unprocessed = selected.findAll { !it.processed }
    def excluded = []
    if (unprocessed) {
        excluded = probeInputs(unprocessed, single)
        def excludedIds = excluded*.studyId as Set
        unprocessed = unprocessed.findAll { !(it.studyId in excludedIds) }
        if (excluded && unprocessed.isEmpty()) {
            error("Every study to load is missing inputs: ${excluded.collect { "${it.studyId} (${it.reason})" }.join('; ')}. " +
                  'Check DATA_ROOT, DECODED_DATA_DIR and CONCEPT_MAPPING_FILE.')
        }
    }
    writePlan(selected, excluded)

    if (excluded) {
        env.EXCLUDED_STUDIES = excluded*.studyId.join(',')
        unstable("${excluded.size()} study/studies excluded for missing inputs: " +
                 excluded.collect { "${it.studyId} (${it.reason})" }.join('; ') +
                 '. Stage the files and re-run the study with STUDY_ID, or mark it not ready.')
    }

    currentBuild.displayName = single
        ? "#${env.BUILD_NUMBER} ${params.STUDY_ID.trim()}"
        : "#${env.BUILD_NUMBER} sweep (${selected.size()} ready, ${unprocessed.size()} to load" +
          (excluded ? ", ${excluded.size()} excluded)" : ')')

    echo "Studies ready (${selected.size()}):"
    def excludedIds = excluded*.studyId as Set
    for (s in selected) {
        def tag = s.processed ? '[processed]' : (s.studyId in excludedIds ? '[EXCLUDED]' : '[new]')
        echo "  ${s.studyId}  ${tag}  ${s.input ? '<- ' + s.input : ''}"
        if (s.dataDir) {
            echo "      decoded data ${s.dataDir} (${s.csvCount ?: 0} CSV, ${s.decodedBytes ?: 0} bytes), mapping ${s.mapping}"
        }
    }
    int processedCount = selected.findAll { it.processed }.size()
    if (processedCount) {
        echo "${processedCount} study/studies already processed — skipping DB population, VCF index creation, and per-study allConcepts"
    }
    if (unprocessed.isEmpty()) {
        echo 'No unprocessed studies to load. Global AllConcepts will still regenerate.'
    }
    return [selected: selected, unprocessed: unprocessed, excluded: excluded]
}

// Every row of the managed inputs CSV at `uri` as [studyId, abv, ready, processed]. Fails on a
// file it cannot read, a UTF-8 BOM, a header without the columns it needs, or a bad Yes/No value.
def readManagedInputs(String uri) {
    // An env var, not GString interpolation, so the URI never becomes shell syntax.
    def text
    withEnv(["MI_URI=${uri}"]) {
        text = sh(returnStdout: true, script: 'aws s3 cp "$MI_URI" - 2>/dev/null || true')
    }
    if (!text?.trim()) {
        error("Could not read managed inputs from ${uri}")
    }
    if (text.startsWith('﻿')) {
        error("Managed inputs ${uri} starts with a UTF-8 byte-order mark, so its first column is not named " +
              "'${MANAGED_INPUTS_COLUMNS[0]}' to a CSV reader. Re-save it without a BOM " +
              '(Excel\'s "CSV UTF-8" format adds one; "CSV (Comma delimited)" does not), or strip its first 3 bytes.')
    }
    def rows
    try {
        rows = parseManagedInputs(text, MANAGED_INPUTS_COLUMNS)
    } catch (IllegalArgumentException e) {
        error("Managed inputs ${uri}: ${e.message}")
    }
    def blankAbv = rows.findAll { !it.abv }
    if (blankAbv) {
        // ManagedInputsService skips them too: they name no study the later stages would see.
        echo "WARNING: ${blankAbv.size()} managed-inputs row(s) with a blank '${MANAGED_INPUTS_COLUMNS[0]}' are ignored: " +
             blankAbv*.studyId.join(', ')
    }
    // In single-study mode the abbreviation is only informative, so keep the row for lookup.
    return params.STUDY_ID?.trim() ? rows : rows.findAll { it.abv }
}

// Probes every study's inputs in S3 in one shell call (probe-study-inputs.sh, PROBE_PARALLELISM at
// once), records each study's SSTR input, decoded data folder (and its size) and mapping, and
// returns the studies missing one as [[studyId, abv, reason]]. A single-study run fails instead.
def probeInputs(List studies, boolean single) {
    def dir = 'downstream-artifacts/study-probe'
    writeFile file: "${dir}/studies.txt", text: studies*.studyId.join('\n') + '\n'
    def dataRoot = params.DATA_ROOT.trim().replaceAll(/\/+$/, '')
    def decoded = params.DECODED_DATA_DIR.trim().replaceAll(/^\/+|\/+$/, '')
    def mapping = params.CONCEPT_MAPPING_FILE.trim().replaceAll(/^\/+/, '')
    int rc
    withEnv(["DATA_ROOT=${dataRoot}", "DECODED_DATA_DIR=${decoded}", "CONCEPT_MAPPING_FILE=${mapping}",
             "PROBE_PARALLELISM=16"]) {
        rc = sh(returnStatus: true,
                script: "bash etl-runners/common/probe-study-inputs.sh ${dir}/studies.txt ${dir}/probe.tsv")
    }
    if (rc != 0) {
        error("Could not probe the studies' inputs under ${dataRoot} (rc=${rc}); see the log above.")
    }
    def probes = parseTsv(readFile("${dir}/probe.tsv"))
    def byId = [:]
    for (p in probes) {
        byId[p.study_id] = p
    }
    def errors = probes.findAll { it.probe_error }
    if (errors) {
        // Not a study's data problem: excluding studies over it would silently shrink the run.
        error("S3 could not be listed for: ${errors.collect { "${it.study_id} (${it.probe_error})" }.join('; ')}")
    }

    def excluded = []
    for (s in studies) {
        def p = byId[s.studyId]
        if (p == null) {
            error("No probe result for ${s.studyId}")
        }
        def studyBase = "${dataRoot}/${s.studyId}"
        def reasons = []
        if (!s.input) {
            // Staged files keep NHLBI's own name (sstr_phs######.v#.txt), so the SSTR cannot be
            // derived from the study id alone: picked the way participants-migration does.
            def file = pickSstr(p.sstr_candidates.split('/') as List, s.studyId)
            if (file) {
                s.input = "${studyBase}/rawData/${file}"
            } else {
                reasons << "no sstr_${s.studyId}.*.txt in ${studyBase}/rawData/"
            }
        }
        // The generator's inputs, checked here so a missing one is caught before anything is
        // provisioned rather than after the SSTR loads.
        s.dataDir = "${studyBase}/${decoded}/"
        s.mapping = "${studyBase}/${mapping}"
        s.csvCount = (p.csv_count ?: '0') as long
        s.decodedBytes = (p.csv_bytes ?: '0') as long
        if (s.csvCount < 1) {
            reasons << "no decoded data CSVs in ${s.dataDir}"
        }
        if (p.mapping_found != '1') {
            reasons << "no concept mapping at ${s.mapping}"
        }
        if (reasons) {
            excluded << [studyId: s.studyId, abv: s.abv ?: '', reason: reasons.join('; ')]
        }
    }
    if (single && excluded) {
        error("Missing inputs for ${excluded[0].studyId}: ${excluded[0].reason}. Stage the files (or pass INPUT " +
              'for the SSTR).')
    }
    return excluded
}

// downstream-artifacts/pipeline-studies.tsv: every study in this run and what the pipeline planned
// for it (load / already_processed / excluded, with why). Read by the end-of-run report.
def writePlan(List selected, List excluded) {
    def why = [:]
    for (e in excluded) {
        why[e.studyId] = e.reason
    }
    def lines = ['study_id\tabv\tplan\treason\tdecoded_bytes\tcsv_count\tsstr_input']
    for (s in selected) {
        def plan = s.processed ? 'already_processed' : (why.containsKey(s.studyId) ? 'excluded' : 'load')
        lines << [s.studyId, s.abv ?: '', plan, why[s.studyId] ?: '', s.decodedBytes ?: '', s.csvCount ?: '',
                  s.input ?: ''].collect { tsvCell(it) }.join('\t')
    }
    writeFile file: 'downstream-artifacts/pipeline-studies.tsv', text: lines.join('\n') + '\n'
}

// The managed-inputs URI for every stage after Resolve studies. Without exclusions this is
// MANAGED_INPUTS unchanged. With them, every later stage (global AllConcepts, VCF indexes, the merge,
// the manifest) reads its ready list from managed inputs on its own and would fail on an excluded
// study -- the manifest finds no consent folders for it -- so they get a copy with the excluded
// studies marked not ready ("No"), written once under this run's report prefix. Same approach as
// Jenkinsfile.migration.
def laterStageManagedInputs() {
    def original = params.MANAGED_INPUTS.trim()
    if (env.LATER_STAGE_MANAGED_INPUTS) {
        return env.LATER_STAGE_MANAGED_INPUTS
    }
    if (!env.EXCLUDED_STUDIES) {
        env.LATER_STAGE_MANAGED_INPUTS = original
        return original
    }
    def bucket = original.substring('s3://'.length()).split('/')[0]
    def effective = "s3://${bucket}/etl-runner/reports/permanent-pipeline/${runTag()}/managed_inputs.csv"
    withEnv(["MI_SRC=${original}", "MI_DST=${effective}"]) {
        // python3's csv module, not a line edit: the sheet's quoted fields can contain commas.
        sh '''#!/bin/bash
            set -euo pipefail
            mkdir -p downstream-artifacts
            aws s3 cp "$MI_SRC" managed_inputs.original.csv --only-show-errors
            python3 - managed_inputs.original.csv downstream-artifacts/managed_inputs.later-stages.csv \
                "$EXCLUDED_STUDIES" <<'PY'
import csv, sys
src, dst, excluded = sys.argv[1], sys.argv[2], set(sys.argv[3].split(","))
# As lenient as the orchestrator's own parser: UTF-8, else cp1252 (Excel's "CSV (Comma
# delimited)" on Windows); blank lines skipped; header names trimmed.
raw = open(src, "rb").read()
try:
    text = raw.decode("utf-8-sig")
except UnicodeDecodeError:
    text = raw.decode("cp1252")
rows = [r for r in csv.reader(text.splitlines()) if any(c.strip() for c in r)]
header = [c.strip() for c in rows[0]]
sid, ready = header.index("Study Identifier"), header.index("Data is ready to process")
marked = 0
for row in rows[1:]:
    if len(row) > max(sid, ready) and row[sid].strip() in excluded:
        row[ready] = "No"
        marked += 1
if marked < len(excluded):
    sys.exit("only %d of %d excluded studies found in managed inputs" % (marked, len(excluded)))
with open(dst, "w", newline="", encoding="utf-8") as fh:
    csv.writer(fh, lineterminator="\\n").writerows(rows)
PY
            aws s3 cp downstream-artifacts/managed_inputs.later-stages.csv "$MI_DST" --only-show-errors
        '''
    }
    echo "Studies excluded for missing inputs (${env.EXCLUDED_STUDIES}): the stages after Resolve studies use " +
         "${effective}, which marks them not ready."
    env.LATER_STAGE_MANAGED_INPUTS = effective
    return effective
}

// -----------------------------------------------------------------------------
// Running child jobs
// -----------------------------------------------------------------------------

// Triggers one child job, copies its artifacts into `target`, and turns its result into this
// build's: UNSTABLE marks this build (and stage) unstable; anything else but SUCCESS fails it, with
// the child's own reason and `failSuffix`. Artifacts are copied before the result is judged, so a
// failed child's report is still archived here.
def runDownstream(String job, List parameters, String target, String failSuffix = '') {
    // propagate must be false: build() throws for ANY downstream result worse than SUCCESS,
    // UNSTABLE included, which would halt the pipeline on warnings.
    def downstream = build(job: job, wait: true, propagate: false, parameters: parameters)
    copyDownstreamArtifacts(job, downstream.number, target)
    if (downstream.result == 'UNSTABLE') {
        unstable("${job} #${downstream.number} completed with warnings")
    } else if (downstream.result != 'SUCCESS') {
        error("${job} #${downstream.number} ended ${downstream.result}${failureDetail(downstream)}${failSuffix}")
    }
    return downstream
}

// One per-study stage (SSTR load, per-study allConcepts): runs cfg.job once per study, at most
// cfg.workers at a time, copies each build's artifacts to downstream-artifacts/<cfg.key>/<study>/,
// prints a summary, and records every study's outcome in downstream-artifacts/study-outcomes/
// <cfg.key>.tsv for the end-of-run report -- also when the stage is aborted part-way.
//
//   cfg.title      'SSTR load'                      summary heading and messages
//   cfg.key        'sstr'                           artifacts folder / outcomes file name
//   cfg.job        params.SSTR_JOB
//   cfg.studies    the studies, in the order to start them
//   cfg.workers    most builds at once
//   cfg.paramsFor  { s -> [string(...), ...] }      the child's parameters for study s
//   cfg.onFailure  { String failedList -> 'message' }  the error() text when any study failed
//
// Fails the stage when any study failed (after every study that was going to run has); marks it
// UNSTABLE when any had warnings. Returns the outcomes.
def runPerStudyStage(Map cfg) {
    def results = java.util.Collections.synchronizedList([])
    // Called with .call: a closure in a Map is not a method the sandbox will dispatch to.
    Closure paramsFor = cfg.paramsFor
    Closure onFailure = cfg.onFailure
    def runOne = { s ->
        echo "--- ${s.studyId} ---"
        def outcome
        try {
            def downstream = build(job: cfg.job, wait: true, propagate: false, parameters: paramsFor.call(s))
            outcome = [studyId: s.studyId, result: downstream.result,
                       build: downstream.number, url: downstream.absoluteUrl]
            outcome.reason = (downstream.result in ['SUCCESS', 'UNSTABLE']) ? '' : failureDetail(downstream)
            copyDownstreamArtifacts(cfg.job, downstream.number, "downstream-artifacts/${cfg.key}/${s.studyId}")
        } catch (err) {
            rethrowIfInterrupted(err)
            outcome = [studyId: s.studyId, result: 'NOT_BUILT', build: null, url: null,
                       reason: " -- could not run: ${err.message}"]
            echo "${s.studyId}: could not run — ${err.message}"
        }
        results << outcome
        echo "${s.studyId}: ${outcome.result}"
        return outcome.result in ['SUCCESS', 'UNSTABLE']
    }

    try {
        runPerStudy(cfg.studies, cfg.workers, runOne)
    } finally {
        writeStudyOutcomes(cfg.key, cfg.studies, results)
    }

    def warned = results.findAll { it.result == 'UNSTABLE' }
    def failed = results.findAll { !(it.result in ['SUCCESS', 'UNSTABLE']) }
    int ok = results.size() - warned.size() - failed.size()
    int skipped = cfg.studies.size() - results.size()
    echo ''
    echo "================ ${cfg.title} summary ================"
    for (r in results) {
        echo String.format('  %-12s %-10s %s%s', r.studyId, r.result, r.url ?: '', r.reason ?: '')
    }
    echo "  ${ok} succeeded, ${warned.size()} with warnings, ${failed.size()} failed" +
         (skipped ? ", ${skipped} not attempted" : '')
    echo '======================================================'

    if (failed) {
        error(onFailure.call(failed.collect { "${it.studyId}${it.reason ?: ''}" }.join('; ')))
    }
    if (warned) {
        unstable("${cfg.title} completed with warnings: ${warned*.studyId.join(', ')}")
    }
    return results
}

// downstream-artifacts/study-outcomes/<key>.tsv: one row per study of a per-study stage, NOT_RUN
// for a study the stage never started (stopped by a failure with CONTINUE_ON_STUDY_FAILURE off, an
// abort, or the timeout).
def writeStudyOutcomes(String key, List studies, List results) {
    def byId = [:]
    for (r in results) {
        byId[r.studyId] = r
    }
    def lines = ['study_id\tresult\tbuild\turl\treason']
    for (s in studies) {
        def r = byId[s.studyId]
        lines << (r == null
            ? [s.studyId, 'NOT_RUN', '', '', '']
            : [r.studyId, r.result, r.build ?: '', r.url ?: '', (r.reason ?: '').replaceFirst(/^ -- /, '')])
            .collect { tsvCell(it) }.join('\t')
    }
    writeFile file: "downstream-artifacts/study-outcomes/${key}.tsv", text: lines.join('\n') + '\n'
}

// Runs body(study) for every study, at most `workers` at a time. body returns true when the study
// succeeded. A fixed pool of workers each takes the next study until none are left. Taking one is
// safe without a lock: pipeline Groovy runs every parallel branch on one thread and only switches
// between them at a step, and there is no step between reading the cursor and advancing it. With
// CONTINUE_ON_STUDY_FAILURE off, a failure stops new studies from starting; builds already running
// finish, and the caller's summary counts the rest as not attempted. An abort or timeout stops every
// worker from starting another study, and is rethrown.
def runPerStudy(List studies, int workerCount, Closure body) {
    int workers = Math.min(workerCount, studies.size())
    def cursor = [index: 0, stop: false]
    def worker = {
        while (!cursor.stop && cursor.index < studies.size()) {
            def s = studies[cursor.index]
            cursor.index = cursor.index + 1
            boolean ok
            try {
                ok = body(s)
            } catch (err) {
                // An abort or the pipeline timeout: start nothing else, in this worker or any other.
                cursor.stop = true
                throw err
            }
            if (!ok && !params.CONTINUE_ON_STUDY_FAILURE) {
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

// Copies a child build's archived artifacts into `target`. A copy failure is reported, not fatal
// (they remain on the child's build page); an abort or timeout is rethrown.
def copyDownstreamArtifacts(String job, number, String target) {
    try {
        copyArtifacts(projectName: job, selector: specific("${number}"), target: target, optional: true)
    } catch (err) {
        rethrowIfInterrupted(err)
        echo "Could not copy artifacts of ${job} #${number} (${err.message}); they remain on that build."
    }
}

// Rethrows err if it is an abort, a timeout(), or any other interrupt of this build, so a catch meant
// for a step's own failure never swallows it. FlowInterruptedException (abort, timeout) extends
// InterruptedException, and instanceof needs no script approval in the sandbox.
def rethrowIfInterrupted(err) {
    if (err instanceof InterruptedException) {
        throw err
    }
}

// Why a downstream build failed, as its own post-failure block recorded it in its description
// (the job's exit meaning, or that it stopped before writing a report): " -- <reason>", or "".
def failureDetail(downstream) {
    String why = downstream.description?.trim()
    return why ? " -- ${why}" : ''
}

// -----------------------------------------------------------------------------
// QA consent counts
// -----------------------------------------------------------------------------

// The last stage: every ready study's participants per consent group in the participant DB, and this
// run's SSTR load reports (qaLoadCounts), compared with dbGaP's counts for its study version.
// Discrepancies, and studies dbGaP could not answer for after the retries, make the child (and this
// build) UNSTABLE; dbGaP answering for no study at all fails it (exit 4). Its reports land in
// downstream-artifacts/qa-consent-counts/ either way, for the end-of-run report.
def runQaConsentCounts(String job) {
    // Every stage that writes the participant DB is done: see backupParticipantDb.
    env.QA_STAGE_REACHED = 'true'
    // Every child parameter is passed: one left out gets the default the child registered on its
    // previous build. One request per study, up to 3 in all (2 retries, 60 s then 120 s apart,
    // Retry-After honoured), 60 s per request; 4 studies at once; no more lookups after 20 studies
    // fail in a row or after 90 minutes. Unanswered studies are UNVERIFIED warnings (UNSTABLE); only
    // dbGaP answering for no study at all is exit 4.
    runDownstream(job, [
        string(name: 'MANAGED_INPUTS', value: laterStageManagedInputs()),
        string(name: 'LOAD_COUNTS', value: qaLoadCounts()),
        string(name: 'STUDY_FILTER', value: ''),
        string(name: 'CONCURRENCY', value: '4'),
        string(name: 'DBGAP_BASE_URL', value: 'https://www.ncbi.nlm.nih.gov/gap/sstr/api/v1/study'),
        string(name: 'DBGAP_MAX_ATTEMPTS', value: '3'),
        string(name: 'DBGAP_INITIAL_BACKOFF_SECONDS', value: '60'),
        string(name: 'DBGAP_MAX_BACKOFF_SECONDS', value: '120'),
        string(name: 'DBGAP_TIMEOUT_SECONDS', value: '60'),
        string(name: 'DBGAP_GIVE_UP_AFTER', value: '20'),
        string(name: 'DBGAP_DEADLINE_SECONDS', value: '5400'),
        string(name: 'INSTANCE_TYPE', value: 't3.medium'),
        string(name: 'JOB_TIMEOUT_SECONDS', value: '7200'),
        string(name: 'RUN_ID', value: "${env.BUILD_TAG}-qa-consent-counts"),
        string(name: 'PREBUILT_IMAGE_TAR', value: env.RUNNER_IMAGE_TAR ?: ''),
        string(name: 'ENV', value: params.ENV),
        booleanParam(name: 'SKIP_TESTS', value: true),
    ], 'downstream-artifacts/qa-consent-counts',
                  ': no consent count was verified against dbGaP. downstream-artifacts/qa-consent-counts/ ' +
                  'has what was compared; a dbGaP outage (exit 4) is retryable.')
}

// This run's participants loaded per consent group, from the SSTR load reports under
// downstream-artifacts/sstr/ (etl-runners/qa-consent-counts/build-load-counts.py), uploaded beside
// this run's report in the DATA_ROOT bucket for the runner to read. A study this run did not load
// is compared DB vs dbGaP only. '' (no load-report check) when the file cannot be built or uploaded:
// the DB-vs-dbGaP check is still worth running.
def qaLoadCounts() {
    def local = 'downstream-artifacts/qa-consent-counts/qa_load_counts.csv'
    def bucket = params.DATA_ROOT.trim().substring('s3://'.length()).split('/')[0]
    def dest = "s3://${bucket}/etl-runner/reports/permanent-pipeline/${runTag()}/qa_load_counts.csv"
    int rc
    withEnv(["QA_LOCAL=${local}", "QA_DEST=${dest}"]) {
        rc = sh(returnStatus: true, script: '''
            python3 etl-runners/qa-consent-counts/build-load-counts.py downstream-artifacts/sstr "$QA_LOCAL" &&
              aws s3 cp "$QA_LOCAL" "$QA_DEST" --only-show-errors
        ''')
    }
    if (rc != 0) {
        echo "WARNING: could not build or upload this run's load counts (rc=${rc}); QA compares the DB with dbGaP only."
        return ''
    }
    return dest
}

// -----------------------------------------------------------------------------
// Parameters
// -----------------------------------------------------------------------------

// The integer value of parameter `name`, which must lie in [min, max], or a clear error.
def intParam(String name, long min, long max) {
    def raw = "${params[name] ?: ''}".trim()
    if (!(raw ==~ /-?[0-9]{1,9}/) || (raw as long) < min || (raw as long) > max) {
        error("${name} must be a whole number from ${min} to ${max}, got '${params[name] ?: ''}'.")
    }
    return raw as long
}

// STUDY_CONCURRENCY: the most per-study builds at once. Always applies to per-study allConcepts
// (it only reads the participant DB); applies to the SSTR load only with PARALLEL_STUDY_LOADS.
def studyConcurrency() {
    return (int) intParam('STUDY_CONCURRENCY', 1, STUDY_CONCURRENCY_MAX)
}

// The SSTR load's concurrency: STUDY_CONCURRENCY with PARALLEL_STUDY_LOADS, else 1.
def sstrConcurrency() {
    return params.PARALLEL_STUDY_LOADS ? studyConcurrency() : 1
}

// How long one study's allConcepts generation may run before it is failed as stuck (exit 124):
// GENERATOR_TIMEOUT_BASE_SECONDS plus GENERATOR_TIMEOUT_SECONDS_PER_GB per GiB (rounded up) of its
// decoded data, clamped to [GENERATOR_TIMEOUT_MIN_SECONDS, GENERATOR_TIMEOUT_MAX_SECONDS].
def generatorTimeoutSeconds(decodedBytes) {
    return scaledTimeout(decodedBytes ?: 0L, intParam('GENERATOR_TIMEOUT_BASE_SECONDS', 0, GENERATOR_TIMEOUT_MAX_SECONDS),
                         intParam('GENERATOR_TIMEOUT_SECONDS_PER_GB', 0, GENERATOR_TIMEOUT_MAX_SECONDS),
                         GENERATOR_TIMEOUT_MIN_SECONDS, GENERATOR_TIMEOUT_MAX_SECONDS)
}

@NonCPS
static long scaledTimeout(long bytes, long base, long perGb, long min, long max) {
    long gib = (Math.max(bytes, 0L) + 1073741823L).intdiv(1073741824L)
    return Math.min(max, Math.max(min, base + gib * perGb))
}

// -----------------------------------------------------------------------------
// Participant DB, runner image, report
// -----------------------------------------------------------------------------

// Makes sure the long-lived participant database is up (see OVERWRITE_DB, DB_RESTORE_FROM and
// IF_LAST_RUN_FAILED). Only once it is does post { always } own the end-of-run backup: a failed
// start changed nothing worth saving.
def startParticipantDb() {
    def downstream = build(
        job: params.DB_START_JOB,
        wait: true,
        propagate: false,
        parameters: [
            string(name: 'RUN_ID', value: "${env.BUILD_TAG}-db"),
            booleanParam(name: 'OVERWRITE', value: params.OVERWRITE_DB),
            string(name: 'RESTORE_FROM', value: params.DB_RESTORE_FROM?.trim() ?: ''),
            string(name: 'IF_LAST_RUN_FAILED', value: params.IF_LAST_RUN_FAILED),
            string(name: 'ENV', value: params.ENV),
        ])
    if (downstream.result != 'SUCCESS') {
        error("${params.DB_START_JOB} #${downstream.number} ended ${downstream.result}${failureDetail(downstream)}: the participant " +
              'database did not come up -- see that job\'s console.')
    }
    env.DB_STARTED = 'true'
}

// Dumps the participant DB to S3 at the end of the run, if this build's start succeeded. The
// database stays up. A SUCCESS or UNSTABLE build overwrites participant_db.tar, the latest clean
// backup; a failed or aborted one writes participant_db_not_promoted.tar instead and marks the live
// database as holding a failed run (the next start acts on IF_LAST_RUN_FAILED) -- unless it got as far
// as QA consent counts, which writes nothing. A failed backup fails the build loudly: this run's
// writes then exist only in the live database.
def backupParticipantDb(String backupJob, String envName) {
    if (env.DB_STARTED != 'true') {
        return
    }
    // QA consent counts runs last and only reads the database: reaching it means every stage that
    // writes the database succeeded, so a QA failure (dbGaP down) must not cost this run's writes.
    boolean promote = currentBuild.currentResult in ['SUCCESS', 'UNSTABLE'] || env.QA_STAGE_REACHED == 'true'
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
        echo "ERROR: ${backupJob} #${downstream.number} ended ${downstream.result}${failureDetail(downstream)}: this run's writes are in the " +
             "live participant database but in no verified backup. Re-run ${backupJob} " +
             "(PROMOTE_BACKUP=${promote}) before the next pipeline run."
    }
}

// Builds the one runner image every downstream runner build uses, as RUNNER_IMAGE_TAR. Set first,
// so a partial upload is still cleaned up by removeRunnerImage in post.
def packageRunnerImage() {
    env.RUNNER_IMAGE_TAR = "hpds-etl-runner-${runTag()}.tar.gz"
    sh "make -C etl-runners/sstr-populate-rds-participants package IMAGE_TAR=${env.RUNNER_IMAGE_TAR}"
}

// Deletes the shared runner image: the tarball in S3, the local one, and the image in this host's
// podman storage. Every downstream build that used it has finished by now.
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

// The run's end-of-run report (etl-runners/pipeline-report/pipeline-report.py), written whatever
// stage the build stopped at: each study's outcome (excluded ones and per-study failure reasons
// included) and each stage's. Lands in downstream-artifacts/pipeline-report/ (archived on this
// build) and in S3 under etl-runner/reports/permanent-pipeline/<build-tag>/pipeline-report/ in the
// DATA_ROOT bucket. A failure here is reported but never changes the build's result.
def writePipelineReport() {
    def root = params.DATA_ROOT?.trim() ?: ''
    def dest = root ==~ /^s3:\/\/[^\/]+.*/
        ? "s3://${root.substring('s3://'.length()).split('/')[0]}/etl-runner/reports/permanent-pipeline/${runTag()}/pipeline-report/"
        : ''
    withEnv(["PR_RUN=${runTag()}", "PR_RESULT=${currentBuild.currentResult}", "PR_DEST=${dest}",
             "PR_FLAGS=${params.PREFLIGHT_ONLY ? '--preflight-only' : ''}"]) {
        int rc = sh(returnStatus: true, script: '''
            python3 etl-runners/pipeline-report/pipeline-report.py $PR_FLAGS downstream-artifacts "$PR_RUN" \
                downstream-artifacts/pipeline-report "$PR_RESULT" || exit 1
            if [ -n "$PR_DEST" ]; then
              aws s3 cp downstream-artifacts/pipeline-report "$PR_DEST" --recursive --no-progress --only-show-errors || exit 2
              echo "Pipeline report uploaded to $PR_DEST"
            fi
        ''')
        if (rc == 1) {
            echo 'WARNING: the pipeline report could not be written; each stage\'s own report is still archived.'
        } else if (rc != 0) {
            echo "WARNING: the pipeline report was written (downstream-artifacts/pipeline-report/) but not uploaded to ${dest}."
        }
    }
}

// BUILD_TAG with only characters safe in an S3 key, a file name and an image name.
def runTag() {
    return env.BUILD_TAG.replaceAll(/[^A-Za-z0-9._-]/, '-')
}

// -----------------------------------------------------------------------------
// Pure helpers (no steps)
// -----------------------------------------------------------------------------

// Rows of a managed inputs CSV as [studyId, abv, ready, processed], skipping rows without a study
// id. Throws IllegalArgumentException on a header missing any of `required` (naming what it found)
// or a ready/processed value that is not Yes/No.
@NonCPS
static List<Map> parseManagedInputs(String text, List<String> required) {
    List<String> header = null
    def rows = []
    for (String line : text.readLines()) {
        if (!line.trim()) continue
        def cols = parseCsvLine(line)
        if (header == null) {
            header = cols
            def missing = required.findAll { !header.contains(it) }
            if (missing) {
                throw new IllegalArgumentException("header is missing column(s) ${missing.collect { "'${it}'" }.join(', ')}; " +
                        "found ${header.collect { "'${it}'" }.join(', ')}. Column names must match exactly.")
            }
            continue
        }
        def col = { String name -> int i = header.indexOf(name); i < cols.size() ? cols[i].trim() : '' }
        def studyId = col('Study Identifier')
        if (!studyId) continue
        rows << [studyId  : studyId, abv: col('Study Abbreviated Name'),
                 ready    : parseYesNo(col('Data is ready to process'), 'Data is ready to process', studyId),
                 processed: parseYesNo(col('Data Processed'), 'Data Processed', studyId)]
    }
    if (header == null) {
        throw new IllegalArgumentException('the file is empty')
    }
    return rows
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
        if (c == (char) '"') {
            inQuotes = !inQuotes
        } else if (c == (char) ',' && !inQuotes) {
            fields << current.toString().trim()
            current = new StringBuilder()
        } else {
            current.append(c)
        }
    }
    fields << current.toString().trim()
    return fields
}

// Rows of a tab-separated text with a header line, as maps keyed by the header.
@NonCPS
static List<Map<String, String>> parseTsv(String text) {
    def lines = text.readLines().findAll { it }
    if (!lines) {
        return []
    }
    def header = lines[0].split('\t', -1) as List
    return lines.drop(1).collect { line ->
        def cols = line.split('\t', -1)
        def row = [:]
        header.eachWithIndex { h, i -> row[h] = i < cols.length ? cols[i] : '' }
        row
    }
}

// A value made safe for one TSV cell: tabs and line breaks become spaces.
@NonCPS
static String tsvCell(value) {
    return "${value == null ? '' : value}".replaceAll(/[\t\r\n]+/, ' ')
}

@NonCPS
static List duplicates(List values) {
    return values.countBy { it }.findAll { k, v -> v > 1 }.keySet().toList()
}

// A copy of `studies` ordered by decoded data size, largest first (ties keep their order), so the
// longest builds start first and do not trail the stage alone.
@NonCPS
static List largestFirst(List studies) {
    def indexed = studies.withIndex().collect { s, i -> [s, i] }
    indexed.sort { a, b -> ((b[0].decodedBytes ?: 0L) as long) <=> ((a[0].decodedBytes ?: 0L) as long) ?: a[1] <=> b[1] }
    return indexed.collect { it[0] }
}

// Picks a study's SSTR from the file names in its rawData folder, by the same rule as
// ParticipantsMigrationJob.isSstrFileFor / isCanonicalSstrName: a .txt naming the study, starting
// sstr_ or bdc-ingestion-only__sstr_ (case-insensitive), the canonical sstr_{phs}.{v}.txt preferred
// over folder-flattened copies. Null when there is none.
@NonCPS
static String pickSstr(List names, String studyId) {
    def sid = studyId.toLowerCase()
    def matches = names.collect { "${it}".trim() }.findAll { n ->
        def l = n.toLowerCase()
        l.endsWith('.txt') && l.contains(sid) && (l.startsWith('sstr_') || l.startsWith('bdc-ingestion-only__sstr_'))
    }
    if (!matches) return null
    def canonical = { String n -> def l = n.toLowerCase(); l.startsWith('sstr_') && !l.startsWith('sstr__') }
    return matches.sort { a, b -> (canonical(a) == canonical(b)) ? a <=> b : (canonical(a) ? -1 : 1) }[0]
}

return this
