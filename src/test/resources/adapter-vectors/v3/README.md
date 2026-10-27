# v3: refusals

One valid single-patient dataset (`manifest-ok.csv`, whose output is `expected-partitions.csv`) and the variants that must be refused or
must fail, each named in `expected-report.json` with the exit code and reason it needs: a manifest md5 that is wrong (exit 3), consent code
`0` (exit 2, accepted with `--allow-consent-codes=0`), a hyphenated partition name (exit 2), a patient id above 2147483647 (a rejected row,
so exit 3 at the default `--max-reject-fraction=0` and success when tolerated), and the cases that need no file: the manifest moving to a
new version while the files are read (exit 3), an output under the legacy bucket's prefix (exit 2) and an output that is not empty (exit 2).
All data is synthetic.
