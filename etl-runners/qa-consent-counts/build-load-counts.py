#!/usr/bin/env python3
"""
Builds the permanent pipeline's --load-counts CSV for qa-consent-counts from the SSTR load reports
the orchestrator collected: for each study, the participants its load wrote per consent group
(metrics.countsByConsentGroup of its sstr-populate-rds-participants JobResult).

Only a successful load counts (status SUCCESS): a failed one rolled back, so the DB holds what it
held before and the report's numbers (if any) describe nothing. A study whose report is missing or
failed is left out, and qa-consent-counts compares it DB vs dbGaP only.

Input:  <sstr-dir>/<study_id>/**/sstr-populate-rds-participants-*.json   (downstream-artifacts/sstr)
Output: <out.csv>  study_id,consent_code,participants_loaded,source   (source is "sstr")
        The header is always written, so an empty run still yields a valid file.

Usage: build-load-counts.py <sstr-dir> <out.csv>
Exit:  0 written | 1 it could not be | 64 usage
"""
import csv
import glob
import json
import os
import re
import sys

STUDY_ID = re.compile(r"phs\d{6}")


def newest(pattern):
    files = [f for f in glob.glob(pattern, recursive=True) if os.path.isfile(f)]
    return max(files, key=lambda f: (os.path.getmtime(f), f)) if files else None


def main(argv):
    if len(argv) != 3:
        print("usage: build-load-counts.py <sstr-dir> <out.csv>", file=sys.stderr)
        return 64
    sstr_dir, out = argv[1], argv[2]
    rows, skipped = [], []
    for study in sorted(os.listdir(sstr_dir)) if os.path.isdir(sstr_dir) else []:
        if not STUDY_ID.fullmatch(study):
            continue
        path = newest(os.path.join(sstr_dir, study, "**", "sstr-populate-rds-participants-*.json"))
        try:
            with open(path, encoding="utf-8") as fh:
                report = json.load(fh)
        except (TypeError, OSError, ValueError):
            skipped.append(f"{study} (no readable report: {path or 'none'})")
            continue
        counts = (report.get("metrics") or {}).get("countsByConsentGroup")
        if report.get("status") != "SUCCESS" or not isinstance(counts, dict):
            skipped.append(f"{study} ({report.get('exitCode') or report.get('status') or 'no counts'})")
            continue
        for code, n in counts.items():
            rows.append([study, str(code).strip(), int(n), "sstr"])
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out + ".partial", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["study_id", "consent_code", "participants_loaded", "source"])
        w.writerows(rows)
    os.replace(out + ".partial", out)
    studies = len({r[0] for r in rows})
    print(f"build-load-counts: {len(rows)} consent group(s) of {studies} study/studies -> {out}")
    if skipped:
        print(f"build-load-counts: no load counts for {len(skipped)} study/studies: {'; '.join(skipped)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # never hide the stage behind a traceback; the stage decides what to do
        print(f"build-load-counts: failed: {e!r}", file=sys.stderr)
        sys.exit(1)
