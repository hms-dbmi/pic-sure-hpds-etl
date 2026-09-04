#!/usr/bin/env python3
"""Generate the harmonized-data consent mapping file (ALS-12727).

Reads a DMC harmonized-example drop and emits a mapping of
    Person.Identity, study_id, consent_code
one row per identity value found in each consent group's Person.tsv.

The drop is partitioned by consent group and the group directory name carries
both the study accession and the consent code, e.g.
    nih-nhlbi-topmed-parent-aric-phs000280-v8-r1-c1
so no external consent source is needed: the path supplies study_id and
consent_code, and mapped-data/Person.tsv supplies the identities.

S3 mode (production; run by the Jenkins job):
    generate_identity_consent_mapping.py \
        --role-arn arn:aws:iam::<acct>:role/nih-nhlbi-TopMed-EC2Access-S3 \
        [--s3-base s3://nih-nhlbi-bdc-harmdata-exchange] \
        [--dataset-prefix BDC-DMC-Harmonization-Examples-YYYYMMDD]
With --dataset-prefix omitted the newest BDC-DMC-Harmonization-Examples-YYYYMMDD
prefix is selected deterministically (max date wins). All AWS access is
read-only: sts assume-role, s3 ls, and s3 cp streamed to stdout.

Local mode (development/testing):
    --local-root DIR                walk the same consent_groups layout on disk
    --local-root DIR --study-id phsNNNNNN --consent-code cN
                                    treat DIR itself as one consent group

Output: identity_consent_mapping.csv (or one file per study with --per-study),
header  Person.Identity,study_id,consent_code.  stdout reports aggregates only;
identity values are never printed.
"""

import argparse
import csv
import io
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

DATASET_RE = re.compile(r"^BDC-DMC-Harmonization-Examples-(\d{8})$")
STUDY_RE = re.compile(r"phs(\d{6})")
CONSENT_RE = re.compile(r"[-_](c\d+)$")
OUTPUT_HEADER = ["Person.Identity", "study_id", "consent_code"]


# --------------------------------------------------------------------------
# Identity parsing (bdchm Person.identity: plain value or JSON-ish list/dict)
# --------------------------------------------------------------------------

def parse_identity(value):
    value = value.strip()
    if not value:
        return []
    if value.startswith("["):
        try:
            parsed = json.loads(value.replace("'", '"'))
            out = []
            for item in parsed if isinstance(parsed, list) else [parsed]:
                if isinstance(item, dict):
                    value_key = next((k for k in item if k.strip().lower() == "value"), None)
                    values = [item[value_key]] if value_key else item.values()
                    out.extend(str(v) for v in values if str(v).strip())
                elif str(item).strip():
                    out.append(str(item).strip())
            return out
        except (json.JSONDecodeError, TypeError):
            inner = re.sub(r"[\[\]{}'\" ]", " ", value)
            return [tok for tok in inner.split() if tok]
    return [value]


def norm(row, name):
    for k, v in row.items():
        if (k or "").strip().lower() == name:
            return (v or "").strip()
    return ""


def parse_group_name(name):
    """Return (study_id, consent_code) or None if the name has no phs/c#."""
    study = STUDY_RE.search(name)
    consent = CONSENT_RE.search(name)
    if not study or not consent:
        return None
    return "phs" + study.group(1), consent.group(1)


# --------------------------------------------------------------------------
# AWS helpers (read-only: sts assume-role, s3 ls, s3 cp to stdout)
# --------------------------------------------------------------------------

def run_aws(cmd, env=None, allow_fail=False):
    result = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if result.returncode != 0:
        if allow_fail:
            return None
        sys.exit(f"{' '.join(cmd[:3])} failed: {result.stderr.strip()}")
    return result.stdout


def assume_role(role_arn):
    out = run_aws(["aws", "sts", "assume-role", "--role-arn", role_arn,
                   "--role-session-name", "identity-consent-mapping",
                   "--output", "json"])
    creds = json.loads(out)["Credentials"]
    env = dict(os.environ)
    env.update({
        "AWS_ACCESS_KEY_ID": creds["AccessKeyId"],
        "AWS_SECRET_ACCESS_KEY": creds["SecretAccessKey"],
        "AWS_SESSION_TOKEN": creds["SessionToken"],
    })
    return env


def s3_ls_dirs(uri, env, allow_fail=False):
    """Names of the immediate sub-prefixes of an s3://bucket/prefix/ URI."""
    out = run_aws(["aws", "s3", "ls", uri if uri.endswith("/") else uri + "/"],
                  env=env, allow_fail=allow_fail)
    if out is None:
        return None
    return [line.split("PRE", 1)[1].strip().rstrip("/")
            for line in out.splitlines() if line.strip().startswith("PRE")]


def s3_find_person_tsvs(group_uri, env):
    """Keys under a consent-group prefix ending in mapped-data/Person.tsv."""
    bucket_and_prefix = group_uri[len("s3://"):]
    bucket, _, prefix = bucket_and_prefix.partition("/")
    out = run_aws(["aws", "s3", "ls", "--recursive", f"s3://{bucket}/{prefix}"], env=env)
    keys = []
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) == 4 and parts[3].endswith("mapped-data/Person.tsv"):
            keys.append(f"s3://{bucket}/{parts[3]}")
    return keys


def stream_s3_tsv(uri, env):
    proc = subprocess.Popen(["aws", "s3", "cp", uri, "-"],
                            stdout=subprocess.PIPE, env=env)
    reader = csv.DictReader(io.TextIOWrapper(proc.stdout, encoding="utf-8-sig"),
                            delimiter="\t")
    yield from reader
    if proc.wait() != 0:
        raise RuntimeError(f"aws s3 cp failed for {uri}")


# --------------------------------------------------------------------------
# Group discovery
# --------------------------------------------------------------------------

def discover_s3_groups(args, env):
    base = args.s3_base.rstrip("/")
    dataset = args.dataset_prefix
    if not dataset:
        dated = [(m.group(1), name) for name in s3_ls_dirs(base, env)
                 if (m := DATASET_RE.match(name))]
        if not dated:
            sys.exit(f"No BDC-DMC-Harmonization-Examples-YYYYMMDD prefixes under {base}")
        dataset = max(dated)[1]
    print(f"dataset: {dataset}")
    groups = []
    for study_dir in s3_ls_dirs(f"{base}/{dataset}", env):
        cg_uri = f"{base}/{dataset}/{study_dir}/consent_groups"
        group_names = s3_ls_dirs(cg_uri, env, allow_fail=True)
        if not group_names:
            print(f"note: {study_dir}: no consent_groups/ prefix, skipped")
            continue
        for name in group_names:
            parsed = parse_group_name(name)
            if not parsed:
                print(f"note: unparseable consent-group name skipped: {name}")
                continue
            for tsv in s3_find_person_tsvs(f"{cg_uri}/{name}/", env):
                groups.append((parsed[0], parsed[1], tsv, "s3"))
    return groups


def discover_local_groups(args):
    root = Path(args.local_root)
    if args.study_id and args.consent_code:
        tsvs = sorted(root.rglob("Person.tsv")) or sys.exit(f"No Person.tsv under {root}")
        return [(args.study_id, args.consent_code, str(t), "local") for t in tsvs]
    groups = []
    for tsv in sorted(root.rglob("Person.tsv")):
        for parent in tsv.parents:
            parsed = parse_group_name(parent.name)
            if parsed:
                groups.append((parsed[0], parsed[1], str(tsv), "local"))
                break
        else:
            print(f"note: Person.tsv with no consent-group ancestor skipped: "
                  f"{tsv.relative_to(root)}")
    return groups


# --------------------------------------------------------------------------
# Main flow
# --------------------------------------------------------------------------

def run(args):
    env = assume_role(args.role_arn) if args.role_arn else dict(os.environ)
    if args.local_root:
        groups = discover_local_groups(args)
    else:
        groups = discover_s3_groups(args, env)
    if not groups:
        sys.exit("No consent groups found")

    rows = set()
    stats = Counter()
    consents_per_identity = defaultdict(set)   # (study, identity) -> {consents}
    for study_id, consent_code, tsv, kind in groups:
        stats["groups"] += 1
        group_rows = set()
        source = stream_s3_tsv(tsv, env) if kind == "s3" else iter_local_tsv(tsv)
        for row in source:
            values = parse_identity(norm(row, "identity"))
            if not values:
                stats["blank_identity_rows"] += 1
                continue
            for value in values:
                key = (value, study_id, consent_code)
                if key in group_rows:
                    stats["in_group_duplicates"] += 1
                group_rows.add(key)
                consents_per_identity[(study_id, value)].add(consent_code)
        rows |= group_rows
        print(f"group {study_id}/{consent_code}: {len(group_rows):,} mapping row(s)")

    stats["rows"] = len(rows)
    stats["cross_consent_identities"] = sum(
        1 for consents in consents_per_identity.values() if len(consents) > 1)

    outdir = Path(args.output_dir)
    outdir.mkdir(parents=True, exist_ok=True)
    written = []
    if args.per_study:
        by_study = defaultdict(list)
        for r in rows:
            by_study[r[1]].append(r)
        for study_id in sorted(by_study):
            path = outdir / f"identity_consent_mapping_{study_id}.csv"
            write_csv(path, by_study[study_id])
            written.append(path)
    else:
        path = outdir / "identity_consent_mapping.csv"
        write_csv(path, rows)
        written.append(path)

    print(f"\nsummary: {stats['groups']} consent group(s), {stats['rows']:,} mapping row(s), "
          f"{stats['blank_identity_rows']:,} blank-identity row(s), "
          f"{stats['in_group_duplicates']:,} in-group duplicate(s), "
          f"{stats['cross_consent_identities']:,} identity(ies) in >1 consent group of a study")
    for path in written:
        print(f"wrote: {path}")
    if stats["cross_consent_identities"]:
        print("WARNING: identities present in more than one consent group of the same "
              "study — likely a source-data issue worth raising with the DMC")
    return 0


def iter_local_tsv(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        yield from csv.DictReader(fh, delimiter="\t")


def write_csv(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(OUTPUT_HEADER)
        w.writerows(sorted(rows))


# --------------------------------------------------------------------------
# Self-test (local mode, plus S3 mode against a mocked aws CLI)
# --------------------------------------------------------------------------

MOCK_AWS = r'''#!/usr/bin/env python3
"""Selftest stand-in for the aws CLI: serves s3 ls/cp from AWS_MOCK_ROOT."""
import os, sys

root = os.environ["AWS_MOCK_ROOT"]
args = sys.argv[1:]


def local(uri):
    return os.path.join(root, uri[len("s3://"):].split("/", 1)[1].strip("/"))


if args[:2] == ["sts", "assume-role"]:
    print('{"Credentials": {"AccessKeyId": "mock", '
          '"SecretAccessKey": "mock", "SessionToken": "mock"}}')
elif args[:2] == ["s3", "ls"]:
    uri = next(a for a in args if a.startswith("s3://"))
    path = local(uri)
    if "--recursive" in args:
        bucket_root = os.path.join(root, "")
        for dirpath, _, files in os.walk(path):
            for f in sorted(files):
                rel = os.path.relpath(os.path.join(dirpath, f), root)
                print(f"2026-01-01 00:00:00        100 {rel}")
    else:
        if not os.path.isdir(path):
            sys.exit(1)
        for name in sorted(os.listdir(path)):
            if os.path.isdir(os.path.join(path, name)):
                print(f"                           PRE {name}/")
elif args[:2] == ["s3", "cp"]:
    sys.stdout.write(open(local(args[2])).read())
else:
    sys.exit(f"mock aws: unhandled command {args!r}")
'''


def make_group(root, dataset, study_dir, group, tsv_text):
    d = root / dataset / study_dir / "consent_groups" / group / "x_BDCHM" / "mapped-data"
    d.mkdir(parents=True)
    (d / "Person.tsv").write_text(tsv_text)
    return d


def selftest_s3(root):
    make_group(root, "BDC-DMC-Harmonization-Examples-20260101", "DMC_OLD",
               "parent-old-phs000009-v1-r1-c1", "id\tidentity\nu0\tSTALE\n")
    make_group(root, "BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
               "parent-x-phs000001-v1-r1-c1",
               "id\tidentity\nu1\tSUB1\nu2\tSUB2\n")
    make_group(root, "BDC-DMC-Harmonization-Examples-20260202", "DMC_Y",
               "parent-y_HMB_-phs000002-v2-p1-c2",
               "id\tidentity\nu3\tSUB3\n")

    bindir = root / "bin"
    bindir.mkdir()
    mock = bindir / "aws"
    mock.write_text(MOCK_AWS)
    mock.chmod(0o755)
    os.environ["PATH"] = f"{bindir}{os.pathsep}{os.environ['PATH']}"
    os.environ["AWS_MOCK_ROOT"] = str(root)

    args = build_parser().parse_args(
        ["--role-arn", "arn:aws:iam::000000000000:role/mock",
         "--s3-base", "s3://mock-bucket", "--output-dir", str(root / "out-s3")])
    assert run(args) == 0
    out = (root / "out-s3" / "identity_consent_mapping.csv").read_text().splitlines()
    assert set(out[1:]) == {
        "SUB1,phs000001,c1", "SUB2,phs000001,c1", "SUB3,phs000002,c2",
    }, out  # only the latest dataset (20260202); STALE from 20260101 excluded


def selftest():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        g1 = root / "DMC_X" / "consent_groups" / "parent-x-phs000001-v1-r1-c1" / "x_BDCHM" / "mapped-data"
        g2 = root / "DMC_X" / "consent_groups" / "parent-x_HMB_-phs000001-v1-p1-c2" / "x_BDCHM" / "mapped-data"
        g1.mkdir(parents=True)
        g2.mkdir(parents=True)
        (g1 / "Person.tsv").write_text(
            "id\tidentity\nu1\tSUB1\nu2\t['SUB2']\nu3\t\nu4\tSUB1\n")
        (g2 / "Person.tsv").write_text(
            "id\tidentity\nu5\tSUB1\nu6\t[{'system': 'dbGaP', 'value': 'SUB9'}]\n")
        args = build_parser().parse_args(
            ["--local-root", str(root), "--output-dir", str(root / "out")])
        code = run(args)
        out = (root / "out" / "identity_consent_mapping.csv").read_text().splitlines()
        assert out[0] == "Person.Identity,study_id,consent_code", out[0]
        body = set(out[1:])
        assert body == {
            "SUB1,phs000001,c1", "SUB2,phs000001,c1",
            "SUB1,phs000001,c2", "SUB9,phs000001,c2",
        }, body
        assert code == 0  # SUB1 spans c1 and c2 -> warned in summary, not fatal
        args = build_parser().parse_args(
            ["--local-root", str(g1.parent.parent), "--study-id", "phs000099",
             "--consent-code", "c9", "--output-dir", str(root / "out2"), "--per-study"])
        assert run(args) == 0
        per_study = (root / "out2" / "identity_consent_mapping_phs000099.csv").read_text()
        assert "SUB1,phs000099,c9" in per_study
    with tempfile.TemporaryDirectory() as tmp:
        selftest_s3(Path(tmp))
    print("selftest OK")


def build_parser():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--role-arn", help="IAM role to assume for all S3 reads")
    p.add_argument("--s3-base", default="s3://nih-nhlbi-bdc-harmdata-exchange")
    p.add_argument("--dataset-prefix", default="",
                   help="dataset prefix name; blank selects the latest by date")
    p.add_argument("--local-root", help="read a local drop tree instead of S3")
    p.add_argument("--study-id", help="with --local-root: treat the tree as one group")
    p.add_argument("--consent-code", help="with --local-root: consent code for --study-id")
    p.add_argument("--per-study", action="store_true",
                   help="write one CSV per study instead of a combined file")
    p.add_argument("--output-dir", default="output")
    p.add_argument("--selftest", action="store_true")
    return p


def main():
    args = build_parser().parse_args()
    if args.selftest:
        selftest()
        return 0
    if bool(args.study_id) != bool(args.consent_code):
        sys.exit("--study-id and --consent-code must be used together")
    if args.study_id and not args.local_root:
        sys.exit("--study-id/--consent-code require --local-root")
    if not args.local_root and not args.role_arn:
        sys.exit("S3 mode requires --role-arn (or use --local-root)")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
