#!/usr/bin/env python3
"""
The permanent pipeline's end-of-run report: for every study in the run, whether it was loaded, and
-- for every study that was not, or was loaded with warnings -- why; plus each stage's outcome.

It reads only what the orchestrator (/Jenkinsfile, etl-runners/common/orchestrator.groovy) already
collected under downstream-artifacts/, so it runs in post { always } whatever stage the build
stopped at: a stage that did not run is reported as not run, and the studies it would have handled
as not reached.

Inputs, under <artifacts-dir> (each optional):
  pipeline-studies.tsv                        every study in the run and its plan (Resolve studies)
        study_id, abv, plan (load|already_processed|excluded), reason, decoded_bytes, csv_count,
        sstr_input
  study-outcomes/sstr.tsv                     each per-study build's result and the child's reason
  study-outcomes/per-study-all-concepts.tsv   (study_id, result, build, url, reason; NOT_RUN = never started)
  sstr/<study>/**/sstr-populate-rds-participants-*.json                the per-study JobResults
  per-study-all-concepts/<study>/**/all-concepts-data-generator-*.json
  all-concepts/**/generate-global-all-concepts-*.json                  the run-wide JobResults
  vcf-indexes/**/create-vcf-indexes-*.json
  merge-allconcepts/**/merge-allconcepts-*.json        (not the -folders.json below)
  merge-allconcepts/**/merge-allconcepts-*-folders.json every folder's outcome, written by a failed merge
                                                        (whose report carries no metrics)
  allconcepts-manifest/**/generate-allconcepts-manifest-*.json
  qa-consent-counts/**/qa-consent-counts-*.json, and beside it qa_consent_counts.csv and
        qa_consent_count_discrepancies.csv (etl-runners/qa-consent-counts/qa_report.py)

Writes, under <out-dir>:
  pipeline_study_outcomes.csv   one row per study
        study_id,abv,status,plan,sstr_result,sstr_build_url,participants,participants_inserted,
        allconcepts_result,allconcepts_build_url,consent_groups,rows_processed,decoded_bytes,
        review_items,reasons
  pipeline-report.json          the same, plus the stage summary, every review item, and the VCF
                                consent groups held back for bad data with the previously published
                                files deleted for them (heldBackVcfGroups) (reportVersion 1)
  pipeline-report.md            for people: the consent count QA's discrepancies first, then the
                                summary, stages, studies needing follow-up, all studies
  qa_consent_counts.csv, qa_consent_count_discrepancies.csv
                                copies of the QA's own CSVs, when it ran (consentCountQa in the JSON)

Study status (most severe wins):
  FAILED                  a stage failed for it (the child's reason is in `reasons`)
  EXCLUDED                its inputs are missing; it was left out of this run (build UNSTABLE)
  NOT_REACHED             the pipeline stopped before a stage that should have handled it (global
                          AllConcepts, the merge and the manifest included: until they ran, its
                          outputs were not published)
  COMPLETE_WITH_WARNINGS  handled, but a stage reported warnings for it
  COMPLETE                every stage handled it cleanly, through the manifest (a pre-flight run:
                          its checks passed)
A run-wide stage that failed without naming a study marks every study FAILED (STAGE_FAILED).
  ALREADY_PROCESSED       marked "Data Processed": not reloaded; only the merge and manifest cover it

Usage: pipeline-report.py [--preflight-only] <artifacts-dir> <run-id> <out-dir> [pipeline-result]
Exit:  0 report written | 1 it could not be | 64 usage. Never fails on what the report says: the
       build's result is decided by the stages; this only explains it.
Set PIPELINE_REPORT_NOW to fix generatedAt (tests).
"""
import csv
import glob
import io
import json
import os
import re
import sys
from collections import OrderedDict
from datetime import datetime, timezone

# The consent count QA section, shared with the migration report.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "qa-consent-counts"))
sys.dont_write_bytecode = True  # no __pycache__ left in the Jenkins workspace
import qa_report  # noqa: E402

REPORT_VERSION = 1
STATUS_ORDER = ["FAILED", "EXCLUDED", "NOT_REACHED", "COMPLETE_WITH_WARNINGS", "COMPLETE", "ALREADY_PROCESSED"]
STUDY_ID = re.compile(r"phs\d{6}")
OK_RESULTS = ("SUCCESS", "UNSTABLE")
PER_STUDY_STAGES = (
    # (key, stage name, report glob under <key>/<study>/)
    ("sstr", "sstr-populate-rds-participants", "**/sstr-populate-rds-participants-*.json"),
    ("per-study-all-concepts", "all-concepts-data-generator", "**/all-concepts-data-generator-*.json"),
)
RUN_STAGES = (
    ("generate-global-all-concepts", "all-concepts/**/generate-global-all-concepts-*.json"),
    ("create-vcf-indexes", "vcf-indexes/**/create-vcf-indexes-*.json"),
    ("merge-allconcepts", "merge-allconcepts/**/merge-allconcepts-*.json"),
    ("generate-allconcepts-manifest", "allconcepts-manifest/**/generate-allconcepts-manifest-*.json"),
)
# Run-wide stages every non-pre-flight run must complete for a study's outputs to be published.
PUBLISHING_STAGES = ("generate-global-all-concepts", "merge-allconcepts", "generate-allconcepts-manifest")
ACTIONS = {
    "EXCLUDED": "Stage the missing input(s), then re-run the study with STUDY_ID (or mark it not ready).",
    "FAILED": "See the reason and the child build's report; fix the cause and re-run the study with STUDY_ID.",
    "NOT_REACHED": "The pipeline stopped before this study was handled: fix the failure above and re-run.",
    "COMPLETE_WITH_WARNINGS": "Check the stage's report for this study before sign-off.",
}


# A failed merge's per-folder outcomes sit beside its report and match the same glob.
MERGE_FOLDERS_SUFFIX = "-folders.json"


def newest(pattern, exclude_suffix=None):
    files = [f for f in glob.glob(pattern, recursive=True) if os.path.isfile(f)
             and not (exclude_suffix and f.endswith(exclude_suffix))]
    # Newest by mtime; the name breaks ties so the choice is stable.
    return max(files, key=lambda f: (os.path.getmtime(f), f)) if files else None


def load_json(path):
    if not path:
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as e:
        print(f"pipeline-report: could not read {path}: {e}", file=sys.stderr)
        return None


def load_tsv(path):
    if not path or not os.path.isfile(path):
        return None
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t", quoting=csv.QUOTE_NONE))


def issues(report):
    out = []
    for phase in ("inputValidation", "outputValidation"):
        out.extend(((report or {}).get(phase) or {}).get("issues") or [])
    return out


def issue_study(issue):
    """The study an issue is about: its context, or the first phs id in its location or message."""
    ctx = issue.get("context") or {}
    if ctx.get("studyId"):
        return ctx["studyId"]
    for text in (ctx.get("folder"), issue.get("location"), issue.get("message")):
        m = STUDY_ID.search(text or "")
        if m:
            return m.group(0)
    return None


def counts(report):
    c = {"error": 0, "warning": 0}
    for phase in ("inputValidation", "outputValidation"):
        for k in c:
            c[k] += (((report or {}).get(phase) or {}).get("counts") or {}).get(k, 0) or 0
    return c


def as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def one_line(text):
    return " ".join(str(text or "").split())


def main(argv):
    args = argv[1:]
    preflight = "--preflight-only" in args
    args = [a for a in args if a != "--preflight-only"]
    if len(args) < 3:
        print("usage: pipeline-report.py [--preflight-only] <artifacts-dir> <run-id> <out-dir> [pipeline-result]",
              file=sys.stderr)
        return 64
    art, run_id, out = args[0], args[1], args[2]
    pipeline_result = args[3] if len(args) > 3 else None
    os.makedirs(out, exist_ok=True)

    def rel(path):
        return os.path.relpath(path, art) if path else None

    plan = load_tsv(os.path.join(art, "pipeline-studies.tsv"))
    outcomes = {key: load_tsv(os.path.join(art, "study-outcomes", f"{key}.tsv")) for key, _, _ in PER_STUDY_STAGES}

    # --- studies ----------------------------------------------------------------------------
    studies = OrderedDict()
    for row in plan or []:
        sid = row["study_id"]
        studies[sid] = OrderedDict(
            studyId=sid, abv=row.get("abv") or "", plan=row.get("plan") or "", status=None,
            decodedBytes=as_int(row.get("decoded_bytes")), sstrInput=row.get("sstr_input") or None,
            stages=OrderedDict(), metrics=OrderedDict(), review=[])

    def review(sid, stage, severity, code, detail):
        studies[sid]["review"].append(OrderedDict(stage=stage, severity=severity, code=code, detail=one_line(detail)))

    for sid, s in studies.items():
        if s["plan"] == "excluded":
            row = next(r for r in plan if r["study_id"] == sid)
            review(sid, "resolve-studies", "EXCLUDED", "MISSING_INPUTS", row.get("reason"))

    # Per-study stages: the orchestrator's outcome (result, child reason), then the child's report.
    for key, stage, pattern in PER_STUDY_STAGES:
        rows = {r["study_id"]: r for r in (outcomes[key] or [])}
        for sid, s in studies.items():
            if s["plan"] != "load":
                continue
            r = rows.get(sid)
            path = newest(os.path.join(art, key, sid, pattern))
            rep = load_json(path)
            st = OrderedDict(result=(r or {}).get("result") or None, build=as_int((r or {}).get("build")),
                             url=(r or {}).get("url") or None, report=rel(path),
                             exitCode=(rep or {}).get("exitCode"), warnings=counts(rep)["warning"] if rep else None)
            s["stages"][stage] = st
            if r is None:
                if outcomes[key] is None:
                    review(sid, stage, "NOT_REACHED", "STAGE_NOT_RUN", f"{stage} did not run")
                continue
            if st["result"] == "NOT_RUN":
                review(sid, stage, "NOT_REACHED", "NOT_STARTED",
                       "not started: the stage stopped first (a failure with CONTINUE_ON_STUDY_FAILURE off, "
                       "an abort, or the timeout)")
            elif st["result"] not in OK_RESULTS:
                review(sid, stage, "FAILED", st["result"] or "FAILED",
                       (r.get("reason") or (rep or {}).get("errorMessage") or f"{stage} ended {st['result']}")
                       + (f" ({st['url']})" if st["url"] else ""))
            for i in issues(rep):
                if i.get("severity") == "WARNING":
                    review(sid, stage, "WARNING", i.get("code"), i.get("message"))
            if st["result"] == "UNSTABLE" and not any(x["stage"] == stage and x["severity"] == "WARNING"
                                                      for x in s["review"]):
                review(sid, stage, "WARNING", "UNSTABLE", f"{stage} completed with warnings"
                       + (f" ({st['url']})" if st["url"] else ""))
            m = (rep or {}).get("metrics") or {}
            if key == "sstr":
                s["metrics"]["participants"] = m.get("distinctParticipants")
                s["metrics"]["participantsInserted"] = m.get("participantsInserted")
            else:
                s["metrics"]["consentGroups"] = m.get("consentGroups")
                s["metrics"]["rowsProcessed"] = m.get("rowsProcessed")

    # Run-wide stages: their summary, and any ERROR/WARNING attributed to a study in this run.
    stages = []
    for key, _, _ in PER_STUDY_STAGES:
        rows = outcomes[key]
        name = dict((k, n) for k, n, _ in PER_STUDY_STAGES)[key]
        if rows is None:
            stages.append(OrderedDict(stage=name, ran=False, result=None, exitCode=None, errors=None, warnings=None,
                                      note="did not run"))
        else:
            by = OrderedDict()
            for r in rows:
                by[r["result"]] = by.get(r["result"], 0) + 1
            stages.append(OrderedDict(stage=name, ran=True, result=None, exitCode=None, errors=None, warnings=None,
                                      note=", ".join(f"{n} {k}" for k, n in by.items())))
    run_reports = {}
    for name, pattern in RUN_STAGES:
        path = newest(os.path.join(art, pattern), MERGE_FOLDERS_SUFFIX if name == "merge-allconcepts" else None)
        rep = load_json(path)
        run_reports[name] = rep
        if rep is None:
            stages.append(OrderedDict(stage=name, ran=False, result=None, exitCode=None, errors=None, warnings=None,
                                      note="no report: the stage did not run (or its artifacts were not copied)"))
            continue
        c = counts(rep)
        stages.append(OrderedDict(stage=name, ran=True, result=rep.get("status"), exitCode=rep.get("exitCode"),
                                  errors=c["error"], warnings=c["warning"], note=one_line(rep.get("errorMessage")) or None,
                                  report=rel(path)))
        for i in issues(rep):
            sev = i.get("severity")
            sid = issue_study(i)
            if sev in ("ERROR", "WARNING") and sid in studies and studies[sid]["plan"] != "excluded":
                review(sid, name, "FAILED" if sev == "ERROR" else "WARNING", i.get("code"), i.get("message"))

    # The consent count QA: its own section, not review items -- a discrepancy is about the participant
    # DB as a whole, and an unverified study (dbGaP down) says nothing about that study's load.
    qa = qa_report.load(art, rel, load_json, preflight)
    stages.append(OrderedDict(stage="qa-consent-counts", ran=qa["ran"], result=qa["status"], exitCode=qa["exitCode"],
                              errors=qa["errors"], warnings=qa["warnings"],
                              note=qa_report.summary_line(qa).split(": ", 1)[1] if qa["ran"] else
                              "no report: the stage did not run (or its artifacts were not copied)",
                              report=qa["report"]))

    # A failed merge names only its first failing folder; its -folders.json has every folder's outcome.
    # Only the one written by the same run as the merge report, never an older build's leftover.
    merge_rep = run_reports.get("merge-allconcepts")
    merge_run = str((merge_rep or {}).get("runId") or "")
    folders_path = newest(os.path.join(art, "merge-allconcepts", "**", f"merge-allconcepts-{merge_run}{MERGE_FOLDERS_SUFFIX}")) \
        if re.fullmatch(r"[A-Za-z0-9._-]+", merge_run) else None
    if folders_path and merge_rep.get("status") != "SUCCESS":
        merge_stage = next(st for st in stages if st["stage"] == "merge-allconcepts")
        merge_stage["foldersReport"] = rel(folders_path)
        for label, entry in ((load_json(folders_path) or {}).get("actions") or {}).items():
            m = STUDY_ID.search(label or "")
            sid = m.group(0) if m else None
            entry = entry or {}
            if entry.get("action") == "FAILED" and sid in studies and studies[sid]["plan"] != "excluded":
                review(sid, "merge-allconcepts", "FAILED", "MERGE_FOLDER_FAILED",
                       f"{label}: {entry.get('error') or 'failed'} (see {rel(folders_path)})")

    # VCF consent groups held back for bad data: not written, and their previously published files
    # deleted. Each is also an ERROR issue (so a FAILED review item above); listed here with what was
    # deleted, which a reviewer needs before re-running.
    vcf_metrics = (run_reports.get("create-vcf-indexes") or {}).get("metrics") or {}
    held_back = [OrderedDict(consentGroup=g.get("consentGroup"), studyId=g.get("studyId"),
                             reasons=g.get("reasons") or [], deletedFiles=g.get("deletedFiles") or [])
                 for g in (vcf_metrics.get("heldBackGroups") or []) if isinstance(g, dict)]

    # The stages that publish every study's outputs (global file, MERGED files, manifest): one that
    # did not run, or failed without naming a study, still left each study unpublished. Not in a
    # pre-flight run, which skips them by design.
    if not preflight:
        for name in PUBLISHING_STAGES:
            rep = run_reports.get(name)
            for sid, s in studies.items():
                if s["plan"] == "excluded":
                    continue
                if rep is None:
                    review(sid, name, "NOT_REACHED", "STAGE_NOT_RUN",
                           f"{name} did not run (or its report was not copied), so this study's outputs were not published")
                elif rep.get("status") != "SUCCESS" and not any(
                        r["stage"] == name and r["severity"] == "FAILED" for r in s["review"]):
                    review(sid, name, "FAILED", "STAGE_FAILED",
                           f"{name} ended {rep.get('exitCode')}: {one_line(rep.get('errorMessage')) or 'see its report'} "
                           "(not specific to this study, but its outputs were not published)")

    # --- status per study -------------------------------------------------------------------
    for sid, s in studies.items():
        sev = {r["severity"] for r in s["review"]}
        if "FAILED" in sev:
            s["status"] = "FAILED"
        elif s["plan"] == "excluded":
            s["status"] = "EXCLUDED"
        elif "NOT_REACHED" in sev:
            s["status"] = "NOT_REACHED"
        elif "WARNING" in sev:
            s["status"] = "COMPLETE_WITH_WARNINGS"
        elif s["plan"] == "already_processed":
            s["status"] = "ALREADY_PROCESSED"
        else:
            s["status"] = "COMPLETE"

    # --- write ------------------------------------------------------------------------------
    by_status = OrderedDict((k, [sid for sid, s in studies.items() if s["status"] == k]) for k in STATUS_ORDER)
    review_rows = [OrderedDict(studyId=sid, abv=s["abv"], status=s["status"], **r)
                   for sid, s in studies.items() for r in s["review"]]
    summary = OrderedDict(
        studies=len(studies),
        studiesByStatus=OrderedDict((k, len(v)) for k, v in by_status.items()),
        excludedStudies=by_status["EXCLUDED"],
        reviewItems=len(review_rows),
        heldBackVcfGroups=len(held_back),
        consentCountQa=qa["outcome"],
        consentCountDiscrepancies=len(qa["discrepancies"]))
    report = OrderedDict(
        reportVersion=REPORT_VERSION, runId=run_id, pipelineResult=pipeline_result, preflightOnly=preflight,
        generatedAt=os.environ.get("PIPELINE_REPORT_NOW")
        or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        studyPlanFound=plan is not None, summary=summary, stages=stages, studyIdsByStatus=by_status,
        studies=list(studies.values()), review=review_rows, heldBackVcfGroups=held_back,
        consentCountQa=qa_report.public(qa, rel))

    def write(name, text):
        path = os.path.join(out, name)
        with open(path + ".partial", "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
        os.replace(path + ".partial", path)

    def csv_text(header, rows):
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(header)
        w.writerows(["" if v is None else v for v in r] for r in rows)
        return buf.getvalue()

    def stage_of(s, name, field):
        return (s["stages"].get(name) or {}).get(field)

    write("pipeline-report.json", json.dumps(report, indent=2) + "\n")
    write("pipeline_study_outcomes.csv", csv_text(
        ["study_id", "abv", "status", "plan", "sstr_result", "sstr_build_url", "participants",
         "participants_inserted", "allconcepts_result", "allconcepts_build_url", "consent_groups",
         "rows_processed", "decoded_bytes", "review_items", "reasons"],
        [[sid, s["abv"], s["status"], s["plan"],
          stage_of(s, "sstr-populate-rds-participants", "result"), stage_of(s, "sstr-populate-rds-participants", "url"),
          s["metrics"].get("participants"), s["metrics"].get("participantsInserted"),
          stage_of(s, "all-concepts-data-generator", "result"), stage_of(s, "all-concepts-data-generator", "url"),
          s["metrics"].get("consentGroups"), s["metrics"].get("rowsProcessed"), s["decodedBytes"],
          len(s["review"]),
          "; ".join(f"{r['stage']}: {r['code']}: {r['detail']}" for r in s["review"])]
         for sid, s in studies.items()]))
    write("pipeline-report.md", markdown(report, studies))
    qa_report.copy_csvs(qa, out)

    print("================ Pipeline report ================")
    print(f"  run {run_id}{' (pre-flight only)' if preflight else ''}: {summary['studies']} studies -- "
          + (", ".join(f"{n} {k}" for k, n in summary["studiesByStatus"].items() if n) or "none"))
    for st in stages:
        print(f"  {st['stage']}: " + (str(st.get("note") or st.get("exitCode") or "ran") if st["ran"] else "did not run"))
    print(f"  {qa_report.summary_line(qa)}")
    if held_back:
        print(f"  VCF consent groups held back: {', '.join(g['consentGroup'] or '?' for g in held_back)} "
              f"({sum(len(g['deletedFiles']) for g in held_back)} previously published file(s) deleted)")
    for k in STATUS_ORDER[:3]:
        if by_status[k]:
            print(f"  {k}: {', '.join(by_status[k])}")
    print(f"  full report: {os.path.join(out, 'pipeline-report.md')}")
    print("=================================================")
    return 0


def markdown(report, studies):
    def cell(v):
        return "" if v is None else str(v).replace("|", "\\|").replace("\n", " ")

    s = report["summary"]
    lines = [f"# Permanent pipeline report: {report['runId']}", "",
             f"Pipeline result: **{report['pipelineResult'] or 'unknown'}**"
             + (" (pre-flight only: inputs were checked, nothing was written)" if report["preflightOnly"] else "")
             + f". Generated {report['generatedAt']}. Machine-readable: `pipeline-report.json`, "
             "`pipeline_study_outcomes.csv`.", ""]
    lines += qa_report.markdown(report["consentCountQa"], cell)
    if not report["studyPlanFound"]:
        lines += ["The build stopped before it resolved its studies (no `pipeline-studies.tsv`).", ""]
    meaning = {"FAILED": "a stage failed for it", "EXCLUDED": "inputs missing; left out of this run",
               "NOT_REACHED": "the pipeline stopped before handling it",
               "COMPLETE_WITH_WARNINGS": "handled; a stage reported warnings",
               "COMPLETE": "handled cleanly", "ALREADY_PROCESSED": "already processed; not reloaded"}
    lines += ["| Study status | Studies |", "|---|---|"]
    for k, n in s["studiesByStatus"].items():
        lines.append(f"| {k} ({meaning[k]}) | {n} |")
    lines += [f"| **Studies** | **{s['studies']}** |", "", "## Stages", "",
              "| Stage | Ran | Result | Exit | Errors | Warnings | Note |", "|---|---|---|---|---|---|---|"]
    for st in report["stages"]:
        lines.append(f"| {st['stage']} | {'yes' if st['ran'] else 'no'} | {cell(st['result'])} | {cell(st['exitCode'])} | "
                     f"{cell(st['errors'])} | {cell(st['warnings'])} | {cell(st['note'])} |")

    if report["heldBackVcfGroups"]:
        lines += ["", "## Held-back VCF consent groups", "",
                  "Not written because of bad data; their previously published `_vcfIndex.tsv` / "
                  "`_SampleIds.csv` were deleted (the versioned bucket keeps the old versions). Fix the "
                  "data and re-run create-vcf-indexes.", "",
                  "| Consent group | Study | Reasons | Deleted files |", "|---|---|---|---|"]
        for g in report["heldBackVcfGroups"]:
            lines.append(f"| {cell(g['consentGroup'])} | {cell(g['studyId'])} | {cell(', '.join(g['reasons']))} | "
                         f"{cell(', '.join(g['deletedFiles']) or 'none (nothing was published before)')} |")

    lines += ["", "## Studies needing follow-up", ""]
    pending = [st for st in studies.values() if st["status"] in ACTIONS]
    if not pending:
        lines.append("None.")
    for k in STATUS_ORDER:
        group = [st for st in pending if st["status"] == k]
        if not group:
            continue
        lines += [f"### {k} ({len(group)})", "", ACTIONS[k], "", "| Study | Abv | Stage | Code | Detail |",
                  "|---|---|---|---|---|"]
        for st in group:
            for r in st["review"]:
                lines.append(f"| {cell(st['studyId'])} | {cell(st['abv'])} | {cell(r['stage'])} | "
                             f"{cell(r['code'])} | {cell(r['detail'])} |")
        lines.append("")

    lines += ["## All studies", "",
              "| Study | Abv | Status | SSTR | Participants | allConcepts | Consent groups | Decoded bytes |",
              "|---|---|---|---|---|---|---|---|"]
    for st in studies.values():
        sstr = st["stages"].get("sstr-populate-rds-participants") or {}
        gen = st["stages"].get("all-concepts-data-generator") or {}
        lines.append(f"| {cell(st['studyId'])} | {cell(st['abv'])} | {cell(st['status'])} | {cell(sstr.get('result'))} | "
                     f"{cell(st['metrics'].get('participants'))} | {cell(gen.get('result'))} | "
                     f"{cell(st['metrics'].get('consentGroups'))} | {cell(st['decodedBytes'])} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # the report must never hide the build's own result behind a traceback
        print(f"pipeline-report: failed to build the report: {e!r}", file=sys.stderr)
        sys.exit(1)
