#!/usr/bin/env python3
"""
The migration pipeline's final report: one place that says, for every ready study, whether it was
migrated, how many participants each of its consent groups holds, and -- for every study that was
not fully migrated -- why, and what kind of follow-up it needs.

It reads only what the orchestrator (Jenkinsfile.migration) already collected under
downstream-artifacts/, so it runs in post { always } whatever stage the build stopped at; a stage
that did not run is reported as not run, and the studies it would have handled as not reached.

Inputs, under <artifacts-dir> (each optional):
  participants-migration/participants-migration-<runId>.json       the JobResult (metrics, issues)
  participants-migration/participants_migration_sources.csv        one row per ready study
  participants-migration/participants_migration_consent_counts.csv participants loaded per consent
  split-allconcepts-report-<tag>.json                              split-report.sh: every study's result
  all-concepts/**/generate-global-all-concepts-*.json
  vcf-indexes/**/create-vcf-indexes-*.json
  merge-allconcepts/**/merge-allconcepts-*.json
  allconcepts-manifest/**/generate-allconcepts-manifest-*.json and allConcepts_manifest.csv

Writes, under <out-dir> (machine-readable first; the .md is the same content for people):
  migration_consent_counts.csv   one row per (study, consent code): the participant counts to
                                 compare against production
        study_id,abv,consent_code,partition,participants_loaded,participants_split,split_rows,
        in_manifest,manifest_version_id,study_status
          participants_loaded  consented participants written to the participant DB for this code
          participants_split   distinct participants in the split allConcepts partition (mapped
                               legacy patients with at least one row) -- the number HPDS will hold
  migration_study_outcomes.csv   one row per ready study: its status and each stage's outcome
        study_id,abv,status,participants_outcome,participant_source,mapping_rows,
        unresolved_mapping_rows,split_outcome,consent_groups,participants_loaded,participants_split,
        review_items
  migration_review.csv           one row per problem, for every study needing follow-up
        study_id,abv,status,stage,category,code,detail,action
  migration-report.json          all of the above plus the stage summary, under one schema
                                 (reportVersion 1)
  migration-report.md            for people: summary, the review list, then the counts

Study status (most severe wins):
  NOT_MIGRATED     its data kept it out (missing, malformed, or mismatched); fix the data, re-run
  RERUN_NEEDED     a stage failed for a reason other than its data, or its output is incomplete
  NOT_REACHED      the pipeline stopped before a stage that should have handled it
  MIGRATED_WITH_GAPS  migrated, but some patients, rows or outputs were dropped; review
  MIGRATED         every stage handled it cleanly
Review categories: MISSING_DATA, MALFORMED_DATA, DATA_MISMATCH, PARTIAL_DATA, PIPELINE_FAILURE,
OUTPUT_WARNING.

Usage: migration-report.py <artifacts-dir> <run-id> <out-dir> [pipeline-result]
Exit:  0 report written | 1 it could not be | 64 usage. Never fails on what the report says: the
       build's result is decided by the stages, this only explains it.
"""
import csv
import glob
import json
import os
import re
import sys
from collections import OrderedDict
from datetime import datetime, timezone

REPORT_VERSION = 1
STATUS_ORDER = ["NOT_MIGRATED", "RERUN_NEEDED", "NOT_REACHED", "MIGRATED_WITH_GAPS", "MIGRATED"]
STUDY_ID = re.compile(r"phs\d{6}")
ACTIONS = {
    "MISSING_DATA": "Stage the missing file(s) for the study, then re-run it (STUDY_FILTER).",
    "MALFORMED_DATA": "Fix or replace the malformed file(s) named in the detail, then re-run the study.",
    "DATA_MISMATCH": "Check the source file's subject ids against the legacy patient mapping, then re-run the study.",
    "PARTIAL_DATA": "Migrated: check whether the dropped patients/rows are expected before sign-off.",
    "PIPELINE_FAILURE": "Not a data problem: re-run the study (STUDY_FILTER); see the stage's logs if it recurs.",
    "OUTPUT_WARNING": "Check the stage's report for this study before sign-off.",
}
STATUS_BY_CATEGORY = {
    "MISSING_DATA": "NOT_MIGRATED",
    "MALFORMED_DATA": "NOT_MIGRATED",
    "DATA_MISMATCH": "NOT_MIGRATED",
    "PIPELINE_FAILURE": "RERUN_NEEDED",
    "PARTIAL_DATA": "MIGRATED_WITH_GAPS",
    "OUTPUT_WARNING": "MIGRATED_WITH_GAPS",
}


def newest(pattern):
    files = glob.glob(pattern, recursive=True)
    return max(files, key=os.path.getmtime) if files else None


def load_json(path):
    if not path:
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as e:
        print(f"migration-report: could not read {path}: {e}", file=sys.stderr)
        return None


def load_csv(path):
    if not path or not os.path.isfile(path):
        return []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        return list(csv.DictReader(fh))


def issues(report):
    if not report:
        return []
    out = []
    for phase in ("inputValidation", "outputValidation"):
        out.extend((report.get(phase) or {}).get("issues") or [])
    return out


def issue_study(issue):
    """The study an issue is about: its context, its folder label, or the first phs id in its message."""
    ctx = issue.get("context") or issue.get("details") or {}
    if ctx.get("studyId"):
        return ctx["studyId"]
    for text in (ctx.get("folder"), issue.get("location"), issue.get("message")):
        m = STUDY_ID.search(text or "")
        if m:
            return m.group(0)
    return None


def stage_summary(name, report, path, ran_note=None):
    if report is None:
        return OrderedDict(stage=name, ran=False, status=None, exitCode=None, report=None,
                           errors=None, warnings=None, note=ran_note or "no report: the stage did not run")
    counts = {"error": 0, "warning": 0}
    for phase in ("inputValidation", "outputValidation"):
        for k in counts:
            counts[k] += ((report.get(phase) or {}).get("counts") or {}).get(k, 0)
    return OrderedDict(stage=name, ran=True, status=report.get("status"), exitCode=report.get("exitCode"),
                       report=path, errors=counts["error"], warnings=counts["warning"],
                       note=report.get("errorMessage"))


def as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def main(argv):
    if len(argv) < 4:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: migration-report.py <artifacts-dir> <run-id> <out-dir> [pipeline-result]", file=sys.stderr)
        return 64
    art, run_id, out = argv[1], argv[2], argv[3]
    pipeline_result = argv[4] if len(argv) > 4 else None
    os.makedirs(out, exist_ok=True)

    pdir = os.path.join(art, "participants-migration")
    p_path = newest(os.path.join(pdir, "participants-migration-*.json"))
    p_report = load_json(p_path)
    p_sources = load_csv(os.path.join(pdir, "participants_migration_sources.csv"))
    p_counts = load_csv(os.path.join(pdir, "participants_migration_consent_counts.csv"))
    s_path = newest(os.path.join(art, "split-allconcepts-report-*.json"))
    s_report = load_json(s_path)
    later = OrderedDict()
    for name, pattern in (("generate-global-all-concepts", "all-concepts/**/generate-global-all-concepts-*.json"),
                          ("create-vcf-indexes", "vcf-indexes/**/create-vcf-indexes-*.json"),
                          ("merge-allconcepts", "merge-allconcepts/**/merge-allconcepts-*.json"),
                          ("generate-allconcepts-manifest",
                           "allconcepts-manifest/**/generate-allconcepts-manifest-*.json")):
        path = newest(os.path.join(art, pattern))
        later[name] = (path, load_json(path))
    manifest_rows = load_csv(newest(os.path.join(art, "allconcepts-manifest/**/allConcepts_manifest.csv")))
    manifest = {r.get("partition"): r for r in manifest_rows}

    stages = [stage_summary("participants-migration", p_report, p_path),
              stage_summary("split-allconcepts", s_report, s_path)]
    stages += [stage_summary(name, rep, path) for name, (path, rep) in later.items()]

    # --- every ready study, with what each stage did ---------------------------------------
    studies = OrderedDict()

    def study(sid, abv=""):
        if sid not in studies:
            studies[sid] = OrderedDict(studyId=sid, abv=abv, status=None, participants=None, split=None,
                                       consents=OrderedDict(), review=[])
        elif abv and not studies[sid]["abv"]:
            studies[sid]["abv"] = abv
        return studies[sid]

    def review(sid, stage, category, code, detail):
        study(sid)["review"].append(OrderedDict(stage=stage, category=category, code=code,
                                                detail=" ".join(str(detail or "").split())))

    def consent(sid, code):
        c = study(sid)["consents"]
        if code not in c:
            c[code] = OrderedDict(consentCode=code, partition=f"{sid}.c{code}", participantsLoaded=None,
                                  participantsSplit=None, splitRows=None, inManifest=None, manifestVersionId=None)
        return c[code]

    p_issues = issues(p_report)
    reason_by_study = {}
    for i in p_issues:
        sid = issue_study(i)
        if sid and i.get("code") in ("STUDY_FAILED", "STUDY_SKIPPED", "STUDY_NEEDS_DATA_REVIEW"):
            reason_by_study[sid] = (i.get("message"), (i.get("context") or {}).get("reason"))

    for row in p_sources:
        sid = row["study_id"]
        s = study(sid, row.get("abv", ""))
        s["participants"] = OrderedDict(outcome=row.get("outcome"), source=row.get("source"),
                                        mappingRows=as_int(row.get("mapping_rows")),
                                        unresolvedMappingRows=as_int(row.get("unresolved_mapping_rows")),
                                        error=row.get("error") or None)
        message, reason = reason_by_study.get(sid, (row.get("error"), None))
        outcome = row.get("outcome")
        if outcome == "skipped":
            review(sid, "participants-migration", "MISSING_DATA", "NO_PARTICIPANT_FILES", message)
        elif outcome == "needs_review":
            review(sid, "participants-migration", "DATA_MISMATCH", "NEEDS_DATA_REVIEW", message)
        elif outcome == "failed":
            review(sid, "participants-migration", "MALFORMED_DATA", reason or "STUDY_FAILED", message)
        elif s["participants"]["unresolvedMappingRows"] > 0:
            review(sid, "participants-migration", "PARTIAL_DATA", "SUBJECTS_NOT_MIGRATED",
                   f"{s['participants']['unresolvedMappingRows']} of "
                   f"{s['participants']['unresolvedMappingRows'] + s['participants']['mappingRows']} patient "
                   "mapping row(s) did not resolve to a loaded, consented subject and were not migrated; "
                   "see participants_migration_file_issues.csv")
    for row in p_counts:
        study(row["study_id"], row.get("abv", ""))
        consent(row["study_id"], row["consent_code"])["participantsLoaded"] = as_int(row.get("participants_loaded"))

    if s_report:
        excluded = set((s_report.get("metrics") or {}).get("excludedStudyIds") or [])
        for r in s_report.get("studies") or []:
            sid = r.get("studyId")
            if not sid:
                continue
            s = study(sid, r.get("abbreviation") or "")
            m = (r.get("job") or {}).get("metrics") or {}
            s["split"] = OrderedDict(outcome=r.get("outcome"), failureStage=r.get("failureStage"),
                                     failureReason=r.get("failureReason"), exitName=r.get("exitName"),
                                     totalRows=m.get("totalRows"), unmappedIds=m.get("unmappedIds"),
                                     noConsentRows=m.get("noConsentRows"), malformedRows=m.get("malformedRows"),
                                     artifacts=(r.get("debug") or {}).get("artifacts"))
            for code, n in (m.get("rowsPerConsent") or {}).items():
                consent(sid, str(code))["splitRows"] = n
            for code, n in (m.get("participantsPerConsent") or {}).items():
                consent(sid, str(code))["participantsSplit"] = n
            outcome = r.get("outcome")
            reason = r.get("failureReason") or (r.get("job") or {}).get("errorMessage")
            if outcome == "FAILED" and sid in excluded:
                missing = r.get("failureStage") == "preflight" and "not found" in (reason or "")
                review(sid, "split-allconcepts", "MISSING_DATA" if missing else "MALFORMED_DATA",
                       "SPLIT_INPUT_MISSING" if missing else
                       ("SPLIT_INPUT_UNUSABLE" if r.get("failureStage") == "preflight" else "SPLIT_DATA_REJECTED"),
                       reason if r.get("failureStage") == "preflight"
                       else f"{reason} ({r.get('exitName') or 'failed'} in {r.get('failureStage')}; artifacts: {s['split']['artifacts']})")
            elif outcome in ("FAILED", "NOT_STARTED"):
                review(sid, "split-allconcepts", "PIPELINE_FAILURE",
                       "SPLIT_NOT_STARTED" if outcome == "NOT_STARTED" else f"SPLIT_{r.get('exitName') or 'FAILED'}",
                       f"{reason} (artifacts: {s['split']['artifacts']})")
            else:
                for key, code, what in (("unmappedIds", "SPLIT_UNMAPPED_IDS", "allConcepts row(s) with a legacy hpds id not in the mapping"),
                                        ("noConsentRows", "SPLIT_ROWS_WITHOUT_CONSENT", "allConcepts row(s) whose participant has no consent in the DB"),
                                        ("malformedRows", "SPLIT_MALFORMED_ROWS", "malformed allConcepts row(s)")):
                    if as_int(m.get(key)) > 0:
                        review(sid, "split-allconcepts", "PARTIAL_DATA", code, f"{m.get(key)} {what} were dropped")

    # Later stages work from the database and S3, not a study list of their own: only their
    # per-study issues are attributed, and the manifest says which partitions were published.
    for name, (path, rep) in later.items():
        for i in issues(rep):
            if i.get("severity") not in ("ERROR", "WARNING"):
                continue
            sid = issue_study(i)
            if not sid or sid not in studies:
                continue
            review(sid, name, "PIPELINE_FAILURE" if i["severity"] == "ERROR" else "OUTPUT_WARNING",
                   i.get("code"), i.get("message"))
    manifest_ran = later["generate-allconcepts-manifest"][1] is not None
    for sid, s in studies.items():
        for code, c in s["consents"].items():
            row = manifest.get(c["partition"])
            if manifest_ran:
                c["inManifest"] = row is not None
                c["manifestVersionId"] = (row or {}).get("version_id") or None

    # --- status per study -----------------------------------------------------------------
    split_ran = s_report is not None
    for sid, s in studies.items():
        statuses = [STATUS_BY_CATEGORY[r["category"]] for r in s["review"]]
        p_ok = s["participants"] is not None and s["participants"]["outcome"] == "success"
        if p_ok and s["split"] is None:
            statuses.append("NOT_REACHED")
            review(sid, "split-allconcepts", "PIPELINE_FAILURE", "NOT_REACHED",
                   "the split stage did not run" if not split_ran else "the split stage has no result for it")
        elif s["participants"] is None:
            statuses.append("NOT_REACHED")
        s["status"] = min(statuses + ["MIGRATED"], key=STATUS_ORDER.index)
        for r in s["review"]:
            r["action"] = ACTIONS.get(r["category"], "")

    # --- write ----------------------------------------------------------------------------
    by_status = OrderedDict((k, sorted(sid for sid, s in studies.items() if s["status"] == k)) for k in STATUS_ORDER)
    consent_rows = [dict(c, studyId=sid, abv=s["abv"], studyStatus=s["status"])
                    for sid, s in studies.items() for c in s["consents"].values()]
    review_rows = [dict(r, studyId=sid, abv=s["abv"], status=s["status"])
                   for sid, s in studies.items() for r in s["review"]]
    loaded = sum(c["participantsLoaded"] or 0 for c in consent_rows)
    split_total = sum(c["participantsSplit"] or 0 for c in consent_rows)
    summary = OrderedDict(
        readyStudies=len(studies),
        studiesByStatus=OrderedDict((k, len(v)) for k, v in by_status.items()),
        consentGroups=len(consent_rows),
        consentGroupsPublished=sum(1 for c in consent_rows if c["inManifest"]),
        participantsLoaded=loaded,
        participantsSplit=split_total,
        reviewItems=len(review_rows),
        reviewItemsByCategory=OrderedDict(
            (cat, sum(1 for r in review_rows if r["category"] == cat)) for cat in ACTIONS))
    report = OrderedDict(
        reportVersion=REPORT_VERSION, runId=run_id, pipelineResult=pipeline_result,
        generatedAt=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        summary=summary, stages=stages, studyIdsByStatus=by_status,
        studies=[dict(s, consents=list(s["consents"].values())) for s in studies.values()],
        consentCounts=consent_rows, review=review_rows)

    def write(name, text):
        path = os.path.join(out, name)
        with open(path + ".partial", "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
        os.replace(path + ".partial", path)

    def csv_text(header, rows):
        import io
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(header)
        w.writerows(["" if v is None else ("true" if v is True else "false" if v is False else v) for v in r]
                     for r in rows)
        return buf.getvalue()

    write("migration-report.json", json.dumps(report, indent=2) + "\n")
    write("migration_consent_counts.csv", csv_text(
        ["study_id", "abv", "consent_code", "partition", "participants_loaded", "participants_split", "split_rows",
         "in_manifest", "manifest_version_id", "study_status"],
        [[c["studyId"], c["abv"], c["consentCode"], c["partition"], c["participantsLoaded"], c["participantsSplit"],
          c["splitRows"], c["inManifest"], c["manifestVersionId"], c["studyStatus"]] for c in consent_rows]))
    write("migration_study_outcomes.csv", csv_text(
        ["study_id", "abv", "status", "participants_outcome", "participant_source", "mapping_rows",
         "unresolved_mapping_rows", "split_outcome", "consent_groups", "participants_loaded", "participants_split",
         "review_items"],
        [[sid, s["abv"], s["status"], (s["participants"] or {}).get("outcome"), (s["participants"] or {}).get("source"),
          (s["participants"] or {}).get("mappingRows"), (s["participants"] or {}).get("unresolvedMappingRows"),
          (s["split"] or {}).get("outcome"), len(s["consents"]),
          sum(c["participantsLoaded"] or 0 for c in s["consents"].values()),
          sum(c["participantsSplit"] or 0 for c in s["consents"].values()), len(s["review"])]
         for sid, s in studies.items()]))
    write("migration_review.csv", csv_text(
        ["study_id", "abv", "status", "stage", "category", "code", "detail", "action"],
        [[r["studyId"], r["abv"], r["status"], r["stage"], r["category"], r["code"], r["detail"], r["action"]]
         for r in review_rows]))
    write("migration-report.md", markdown(report, studies))

    print("================ Migration report ================")
    print(f"  run {run_id}: {summary['readyStudies']} ready studies -- "
          + ", ".join(f"{n} {k}" for k, n in summary["studiesByStatus"].items() if n))
    print(f"  {summary['consentGroups']} consent group(s); {loaded} participant(s) loaded, {split_total} split")
    for st in stages:
        print(f"  {st['stage']}: " + (f"{st['exitCode']}" if st["ran"] else "did not run"))
    for k in STATUS_ORDER[:3]:
        if by_status[k]:
            print(f"  {k}: {', '.join(by_status[k])}")
    print(f"  full report: {os.path.join(out, 'migration-report.md')}")
    print("==================================================")
    return 0


def markdown(report, studies):
    def cell(v):
        return "" if v is None else str(v).replace("|", "\\|").replace("\n", " ")

    s = report["summary"]
    lines = [f"# Migration report: {report['runId']}", "",
             f"Pipeline result: **{report['pipelineResult'] or 'unknown'}**. Generated {report['generatedAt']}. "
             "Machine-readable: `migration-report.json`, `migration_consent_counts.csv`, "
             "`migration_study_outcomes.csv`, `migration_review.csv`.", "",
             "| Study status | Studies |", "|---|---|"]
    meaning = {"NOT_MIGRATED": "data missing, malformed or mismatched -- needs data work",
               "RERUN_NEEDED": "a stage failed for a non-data reason -- re-run",
               "NOT_REACHED": "the pipeline stopped before handling it",
               "MIGRATED_WITH_GAPS": "migrated; some patients/rows/outputs dropped -- review",
               "MIGRATED": "migrated cleanly"}
    for k, n in s["studiesByStatus"].items():
        lines.append(f"| {k} ({meaning[k]}) | {n} |")
    lines += [f"| **Ready studies** | **{s['readyStudies']}** |", "",
              f"Consent groups: {s['consentGroups']} ({s['consentGroupsPublished']} in the published manifest). "
              f"Participants loaded: {s['participantsLoaded']}. Participants in split partitions: {s['participantsSplit']}.",
              "", "## Stages", "", "| Stage | Ran | Exit | Errors | Warnings | Note |", "|---|---|---|---|---|---|"]
    for st in report["stages"]:
        lines.append(f"| {st['stage']} | {'yes' if st['ran'] else 'no'} | {cell(st['exitCode'])} | "
                     f"{cell(st['errors'])} | {cell(st['warnings'])} | {cell(st['note'])} |")

    lines += ["", "## Studies needing follow-up", ""]
    pending = [st for st in studies.values() if st["status"] != "MIGRATED"]
    if not pending:
        lines.append("None: every ready study was migrated cleanly.")
    for k in STATUS_ORDER[:-1]:
        group = [st for st in pending if st["status"] == k]
        if not group:
            continue
        lines += ["", f"### {k} ({len(group)})", "", "| Study | Abv | Stage | Category | Code | Detail |",
                  "|---|---|---|---|---|---|"]
        for st in group:
            for r in st["review"] or [OrderedDict(stage="", category="", code="", detail="")]:
                lines.append(f"| {cell(st['studyId'])} | {cell(st['abv'])} | {cell(r['stage'])} | "
                             f"{cell(r['category'])} | {cell(r['code'])} | {cell(r['detail'])} |")
        actions = sorted({r["category"] for st in group for r in st["review"]})
        lines += [""] + [f"- **{a}**: {ACTIONS[a]}" for a in actions if a in ACTIONS]

    lines += ["", "## Participants per consent group", "",
              "`participants_loaded`: consented participants written to the participant DB. "
              "`participants_split`: distinct participants in the split allConcepts partition -- the count "
              "to compare against production.", "",
              "| Partition | Abv | Loaded | Split | Split rows | In manifest | Study status |", "|---|---|---|---|---|---|---|"]
    for c in report["consentCounts"]:
        lines.append(f"| {cell(c['partition'])} | {cell(c['abv'])} | {cell(c['participantsLoaded'])} | "
                     f"{cell(c['participantsSplit'])} | {cell(c['splitRows'])} | "
                     f"{'' if c['inManifest'] is None else ('yes' if c['inManifest'] else '**no**')} | "
                     f"{cell(c['studyStatus'])} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # the report must never hide the build's own result behind a traceback
        print(f"migration-report: failed to build the report: {e!r}", file=sys.stderr)
        sys.exit(1)
