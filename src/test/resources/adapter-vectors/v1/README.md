# v1: two studies, three consents, one patient in both studies

Study `phs000000` has consent groups `c1` and `c2`, study `phs999999` has `c1`; patients 1 and 3 hold `phs000000.c1`, patient 2
holds `phs000000.c2`, patient 4 holds `phs999999.c1`, and patient 3 holds `phs999999.c1` as well. The inputs are in Sharra's
output layout (manifest with two extra trailing columns, every field quoted, `µ` paths, timestamp `"0"`); patient 3's source-id
rows occur once per study, so they are patient-level and are copied once to each partition, while its `_consents` and
`_studies_consents` rows follow their own study. `expected-partitions.csv` lists every output row sorted as `partition,patient,path,nval,tval,ts`
and `expected-report.json` the counts the report and `split_manifest.json` must show (default parameters). All data is synthetic.
