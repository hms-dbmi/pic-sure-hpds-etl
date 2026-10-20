"""
The "Consent count QA (dbGaP)" section of both end-of-run reports (etl-runners/pipeline-report/
pipeline-report.py and etl-runners/migration-report/migration-report.py), from what the
orchestrator copied of the qa-consent-counts build into <artifacts-dir>/qa-consent-counts/:

  **/qa-consent-counts-<runId>.json            the JobResult (metrics, issues, exit code)
  **/qa_consent_count_discrepancies.csv        beside it: every non-MATCH row
  **/qa_consent_counts.csv                     beside it: every row

load() reads them into one JSON-able dict (the reports' "consentCountQa" key), markdown() renders it
for the top of the report, and copy_csvs() copies both CSVs into the report's output directory.
Never raises on missing or malformed input: a QA that did not run, or failed, is reported as such.
"""
import csv
import glob
import os
import shutil
from collections import OrderedDict

REPORT_GLOB = os.path.join("qa-consent-counts", "**", "qa-consent-counts-*.json")
COUNTS_CSV = "qa_consent_counts.csv"
DISCREPANCIES_CSV = "qa_consent_count_discrepancies.csv"
# The markdown lists at most this many discrepancies; the CSV copy has them all.
MAX_ROWS = 200
OK_EXIT = ("SUCCESS", "SUCCESS_WITH_WARNINGS")


def _newest(pattern):
    files = [f for f in glob.glob(pattern, recursive=True) if os.path.isfile(f)]
    return max(files, key=lambda f: (os.path.getmtime(f), f)) if files else None


def _rows(path):
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, newline="", encoding="utf-8") as fh:
            return list(csv.DictReader(fh))
    except (OSError, ValueError, csv.Error):
        return None


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def load(art, rel, load_json, preflight=False):
    """The QA section's data. `rel` makes a path relative to the artifacts dir; `load_json` reads a report."""
    path = _newest(os.path.join(art, REPORT_GLOB))
    rep = load_json(path) if path else None
    qa = OrderedDict(ran=rep is not None, outcome=None, report=rel(path) if rep is not None else None,
                     status=None, exitCode=None, errorMessage=None, errors=None, warnings=None, loadCounts=None, studiesChecked=None, studiesVerified=None,
                     consentsCompared=None, consentsMatching=None, discrepancyCount=None,
                     discrepanciesByType=OrderedDict(), studiesWithDiscrepancies=[], unverifiedStudies=[],
                     discrepancies=[], csvs=[])
    if rep is None:
        qa["outcome"] = "SKIPPED" if preflight else "NOT_RUN"
        return qa
    m = rep.get("metrics") or {}
    counts = {"error": 0, "warning": 0}
    for phase in ("inputValidation", "outputValidation"):
        for k in counts:
            counts[k] += _int(((rep.get(phase) or {}).get("counts") or {}).get(k)) or 0
    qa.update(status=rep.get("status"), errors=counts["error"], warnings=counts["warning"],
              loadCounts=m.get("loadCounts") or None, exitCode=rep.get("exitCode"), errorMessage=" ".join(str(rep.get("errorMessage") or "").split()) or None,
              studiesChecked=_int(m.get("studiesChecked")), studiesVerified=_int(m.get("studiesVerified")),
              consentsCompared=_int(m.get("consentsCompared")), consentsMatching=_int(m.get("consentsMatching")),
              discrepancyCount=_int(m.get("discrepancies")),
              discrepanciesByType=OrderedDict((k, v) for k, v in (m.get("discrepanciesByType") or {}).items()),
              studiesWithDiscrepancies=list(m.get("studiesWithDiscrepancies") or []),
              unverifiedStudies=list(m.get("unverifiedStudies") or []))
    folder = os.path.dirname(path)
    qa["csvs"] = [os.path.join(folder, f) for f in (COUNTS_CSV, DISCREPANCIES_CSV)
                  if os.path.isfile(os.path.join(folder, f))]
    for r in _rows(os.path.join(folder, DISCREPANCIES_CSV)) or []:
        qa["discrepancies"].append(OrderedDict(
            studyId=r.get("study_id"), version=r.get("version") or None, consentCode=r.get("consent_code") or None,
            consentShortName=r.get("consent_short_name") or None, dbgapSubjects=_int(r.get("dbgap_subjects")),
            dbParticipants=_int(r.get("db_participants")), loadParticipants=_int(r.get("load_participants")),
            loadSource=r.get("load_source") or None, status=r.get("status"), note=r.get("note") or None))
    if rep.get("exitCode") not in OK_EXIT:
        qa["outcome"] = "FAILED"
    elif qa["discrepancyCount"] or any(d["status"] != "MATCH" for d in qa["discrepancies"]):
        qa["outcome"] = "DISCREPANCIES"
    else:
        qa["outcome"] = "ALL_MATCH"
    return qa


def summary_line(qa):
    """One line for the console summary."""
    if qa["outcome"] == "NOT_RUN":
        return "consent count QA: did not run"
    if qa["outcome"] == "SKIPPED":
        return "consent count QA: skipped (pre-flight only)"
    if qa["outcome"] == "FAILED":
        return (f"consent count QA: FAILED ({qa['exitCode']}); unverified: {', '.join(qa['unverifiedStudies']) or 'n/a'}; "
                f"{qa['discrepancyCount'] or 0} discrepancy(ies) among the studies it did check")
    if qa["outcome"] == "DISCREPANCIES":
        return (f"consent count QA: {qa['discrepancyCount']} discrepancy(ies) in "
                f"{', '.join(qa['studiesWithDiscrepancies']) or '?'}")
    return f"consent count QA: all {qa['consentsCompared']} consent group(s) match dbGaP"


def markdown(qa, cell):
    """The section's markdown lines, from its heading on."""
    lines = ["## Consent count QA (dbGaP)", ""]
    by_type = ", ".join(f"{k} {v}" for k, v in qa["discrepanciesByType"].items())
    checked = (f"{qa['studiesChecked']} ready study/studies checked, {qa['studiesVerified']} answered by dbGaP, "
               f"{qa['consentsCompared']} consent group(s) compared")
    if qa["outcome"] == "NOT_RUN":
        lines += ["**QA did not run**: the pipeline stopped before the QA consent counts stage (or its report "
                  "was not copied), so no consent count was checked against dbGaP.", ""]
        return lines
    if qa["outcome"] == "SKIPPED":
        lines += ["Skipped: a pre-flight run does not start the participant database.", ""]
        return lines
    if qa["outcome"] == "FAILED":
        lines += [f"**QA failed: {cell(qa['exitCode'])}**: {cell(qa['errorMessage']) or 'see its report'}"
                  + (f" Unverified: {', '.join(qa['unverifiedStudies'])}." if qa["unverifiedStudies"] else ""),
                  "", f"Before failing: {checked}; {qa['discrepancyCount'] or 0} discrepancy(ies)"
                  + (f" ({by_type})" if by_type else "") + ".", ""]
    elif qa["outcome"] == "ALL_MATCH":
        lines += [f"All {qa['consentsCompared']} consent group(s) match dbGaP"
                  + (" and this run's load report" if qa["loadCounts"] else "")
                  + f" ({qa['studiesChecked']} ready study/studies checked).", ""]
        return lines
    else:
        lines += [f"**{qa['discrepancyCount']} discrepancy(ies)** in {len(qa['studiesWithDiscrepancies'])} "
                  f"study/studies ({by_type}); {checked}. The participant DB is authoritative; "
                  "`load` is what this run's loads reported.", ""]
    shown = qa["discrepancies"][:MAX_ROWS]
    if shown:
        lines += ["| Study | Version | Consent | Name | dbGaP | DB | Load | Status | Note |",
                  "|---|---|---|---|---|---|---|---|---|"]
        for d in shown:
            lines.append(f"| {cell(d['studyId'])} | {cell(d['version'])} | {cell(d['consentCode'])} | "
                         f"{cell(d['consentShortName'])} | {cell(d['dbgapSubjects'])} | {cell(d['dbParticipants'])} | "
                         f"{cell(d['loadParticipants'])} | {cell(d['status'])} | {cell(d['note'])} |")
        if len(qa["discrepancies"]) > MAX_ROWS:
            lines.append(f"\n... and {len(qa['discrepancies']) - MAX_ROWS} more in `{DISCREPANCIES_CSV}`.")
        lines.append("")
    lines += [f"Every row: `{COUNTS_CSV}`; discrepancies only: `{DISCREPANCIES_CSV}`.", ""]
    return lines


def copy_csvs(qa, out):
    """Copies the QA build's CSVs into the report's output directory."""
    for src in qa.get("csvs") or []:
        shutil.copyfile(src, os.path.join(out, os.path.basename(src)))


def public(qa, rel):
    """The section as stored in the report JSON (CSV paths relative to the artifacts dir)."""
    out = OrderedDict(qa)
    out["csvs"] = [rel(p) for p in qa.get("csvs") or []]
    return out
