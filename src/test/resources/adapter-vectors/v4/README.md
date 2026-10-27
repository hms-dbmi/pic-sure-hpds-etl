# v4: routing rejections and study-specific ids

`inputs/` holds one row for each routing reject: a data row whose first node is another study (`STUDY_PARTITION_MISMATCH`), a data row of a
patient with no `_consents` row for that partition (`DATA_ROW_WITHOUT_CONSENT`), a `_consents` row naming a partition the manifest does
not list (`CONSENT_NOT_IN_MANIFEST`) and a global row with an unknown path (`UNKNOWN_GLOBAL_PATH`); run with `--max-reject-fraction=1`
it succeeds with the four accepted rows of `expected-partitions.csv`. `inputs-study-specific-ids/` holds a patient in both studies whose
source-subject-id row differs per study, so the ids are not patient-level: `--multi-study-ids=copy` stops with exit 3
`ID_ROWS_NOT_PATIENT_LEVEL`. All data is synthetic.
