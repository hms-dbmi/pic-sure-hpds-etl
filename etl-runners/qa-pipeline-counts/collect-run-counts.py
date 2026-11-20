#!/usr/bin/env python3
"""
Collects a pipeline run's per-consent counts from what its stages left under downstream-artifacts/,
into the CSV qa-pipeline-counts reads as --run-counts. Run by the orchestrator at the end of the
run, after the final report is written.

  migration (Jenkinsfile.migration):
    participants-migration          participants-migration/participants_migration_consent_counts.csv
                                    participants_loaded
    split-allconcepts               split-allconcepts-report-<tag>.json  studies[].job.metrics.
                                    participantsPerConsent (studies whose split succeeded)
    migration-report:loaded         migration-report/migration_consent_counts.csv participants_loaded
    migration-report:split          ... participants_split
  permanent (Jenkinsfile):
    sstr-load                       sstr/<study>/**/sstr-populate-rds-participants-*.json
                                    metrics.countsByConsentGroup (loads that did not fail)
    all-concepts-data-generator     per-study-all-concepts/<study>/**/all-concepts-data-generator-*.json
                                    metrics.participantsPerConsent (runs that did not fail)
    pipeline-report:loaded          pipeline-report/pipeline_consent_counts.csv participants_loaded
    pipeline-report:written         ... participants_written

Writes <out-csv>: study_id,consent_code,source,participants,load_source -- one row per (study,
consent code, source) a source reported a count for. load_source is what participants-migration
loaded the study from (sstr or multi; a MULTI load drops consent 0), on its rows; blank elsewhere.
A missing input is skipped (the stage did not run, or did not get that far): every study is then
still compared DB vs dbGaP. Two counts for one study, consent code ("1" and "c1" are one) and
source are an error, not summed: one of the stage's reports is wrong.

Usage: collect-run-counts.py <migration|permanent> <artifacts-dir> <out-csv>
Exit:  0 written (possibly with no rows) | 1 could not be written, or a duplicate count | 64 usage
"""
import csv
import glob
import json
import os
import re
import sys

FAILED = ("FAILED",)


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
        print(f"collect-run-counts: skipping unreadable {path}: {e}", file=sys.stderr)
        return None


def load_csv(path):
    if not path or not os.path.isfile(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def whole(v):
    """A count as an int, or None for blank or anything not a whole number."""
    s = "" if v is None else str(v).strip()
    return int(s) if s.isdigit() else None


def consent_key(code):
    """The consent group a code names, as qa-pipeline-counts matches them: "c1", "01" and "1" are "1"."""
    m = re.fullmatch(r"[cC]?(\d{1,18})", code.strip())
    return str(int(m.group(1))) if m else code.strip()


def from_metrics(rows, source, sid, per_consent):
    if isinstance(per_consent, dict):
        for code, n in per_consent.items():
            if whole(n) is not None:
                rows.append((sid, str(code), source, whole(n), ""))


def from_csv(rows, path, columns, load_source_column=None):
    """columns: (csv column, source) pairs. load_source_column: the column naming what the study was loaded from."""
    for r in load_csv(path):
        sid = (r.get("study_id") or "").strip()
        code = (r.get("consent_code") or "").strip()
        if not sid or not code:
            continue
        loaded_from = (r.get(load_source_column) or "").strip().lower() if load_source_column else ""
        for column, source in columns:
            n = whole(r.get(column))
            if n is not None:
                rows.append((sid, code, source, n, loaded_from if loaded_from in ("sstr", "multi") else ""))


def per_study_reports(rows, art, key, pattern, metric, source):
    """The newest report of each study under <art>/<key>/<study>/, unless that run failed."""
    base = os.path.join(art, key)
    if not os.path.isdir(base):
        return
    for sid in sorted(os.listdir(base)):
        rep = load_json(newest(os.path.join(base, sid, pattern)))
        if rep and rep.get("status") not in FAILED:
            from_metrics(rows, source, sid, (rep.get("metrics") or {}).get(metric))


def collect(pipeline, art):
    rows = []
    if pipeline == "migration":
        from_csv(rows, os.path.join(art, "participants-migration", "participants_migration_consent_counts.csv"),
                 [("participants_loaded", "participants-migration")], load_source_column="source")
        split = load_json(newest(os.path.join(art, "split-allconcepts-report-*.json"))) or {}
        for s in split.get("studies") or []:
            if s.get("outcome") in ("SUCCESS", "SUCCESS_WITH_WARNINGS") and s.get("studyId"):
                from_metrics(rows, "split-allconcepts", s["studyId"],
                             ((s.get("job") or {}).get("metrics") or {}).get("participantsPerConsent"))
        from_csv(rows, os.path.join(art, "migration-report", "migration_consent_counts.csv"),
                 [("participants_loaded", "migration-report:loaded"), ("participants_split", "migration-report:split")])
    else:
        per_study_reports(rows, art, "sstr", "**/sstr-populate-rds-participants-*.json",
                          "countsByConsentGroup", "sstr-load")
        per_study_reports(rows, art, "per-study-all-concepts", "**/all-concepts-data-generator-*.json",
                          "participantsPerConsent", "all-concepts-data-generator")
        from_csv(rows, os.path.join(art, "pipeline-report", "pipeline_consent_counts.csv"),
                 [("participants_loaded", "pipeline-report:loaded"), ("participants_written", "pipeline-report:written")])
    return rows


def duplicates(rows):
    """Each (study, consent group, source) counted more than once, as 'study c<code> source'."""
    seen, dups = set(), []
    for sid, code, source, _, _ in rows:
        key = (sid, consent_key(code), source)
        if key in seen:
            dups.append(f"{sid} c{key[1]} {source}")
        seen.add(key)
    return dups


def main(argv):
    if len(argv) != 4 or argv[1] not in ("migration", "permanent"):
        print("usage: collect-run-counts.py <migration|permanent> <artifacts-dir> <out-csv>", file=sys.stderr)
        return 64
    pipeline, art, out = argv[1], argv[2], argv[3]
    rows = collect(pipeline, art)
    dups = duplicates(rows)
    if dups:
        print("collect-run-counts: more than one count for " + ", ".join(dups[:20])
              + (" ..." if len(dups) > 20 else "") + "; not written (QA then compares the DB with dbGaP only)",
              file=sys.stderr)
        return 1
    try:
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out + ".partial", "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(["study_id", "consent_code", "source", "participants", "load_source"])
            w.writerows(rows)
        os.replace(out + ".partial", out)
    except OSError as e:
        print(f"collect-run-counts: could not write {out}: {e}", file=sys.stderr)
        return 1
    by_source = {}
    for sid, _, source, _, _ in rows:
        by_source.setdefault(source, set()).add(sid)
    print(f"collect-run-counts ({pipeline}): {len(rows)} count(s) -- "
          + (", ".join(f"{s}: {len(ids)} study/studies" for s, ids in by_source.items()) or "none found"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
