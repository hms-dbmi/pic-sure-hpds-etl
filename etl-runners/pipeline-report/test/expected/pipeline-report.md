# Permanent pipeline report: jenkins-test-1

Pipeline result: **FAILURE**. Generated 2026-10-16T00:00:00Z. Machine-readable: `pipeline-report.json`, `pipeline_study_outcomes.csv`.

| Study status | Studies |
|---|---|
| FAILED (a stage failed for it) | 1 |
| EXCLUDED (inputs missing; left out of this run) | 1 |
| NOT_REACHED (the pipeline stopped before handling it) | 4 |
| COMPLETE_WITH_WARNINGS (handled; a stage reported warnings) | 0 |
| COMPLETE (handled cleanly) | 0 |
| ALREADY_PROCESSED (already processed; not reloaded) | 0 |
| **Studies** | **6** |

## Stages

| Stage | Ran | Result | Exit | Errors | Warnings | Note |
|---|---|---|---|---|---|---|
| sstr-populate-rds-participants | yes |  |  |  |  | 3 SUCCESS, 1 UNSTABLE |
| all-concepts-data-generator | yes |  |  |  |  | 2 SUCCESS, 1 FAILURE, 1 NOT_RUN |
| generate-global-all-concepts | yes | SUCCESS | SUCCESS | 0 | 0 |  |
| create-vcf-indexes | yes | SUCCESS | SUCCESS_WITH_WARNINGS | 0 | 1 |  |
| merge-allconcepts | no |  |  |  |  | no report: the stage did not run (or its artifacts were not copied) |
| generate-allconcepts-manifest | no |  |  |  |  | no report: the stage did not run (or its artifacts were not copied) |

## Studies needing follow-up

### FAILED (1)

See the reason and the child build's report; fix the cause and re-run the study with STUDY_ID.

| Study | Abv | Stage | Code | Detail |
|---|---|---|---|---|
| phs000003 | THREE | all-concepts-data-generator | FAILURE | DATA ERROR (exit 3): malformed decoded data (https://j/gen/22/) |
| phs000003 | THREE | merge-allconcepts | STAGE_NOT_RUN | merge-allconcepts did not run (or its report was not copied), so this study's outputs were not published |
| phs000003 | THREE | generate-allconcepts-manifest | STAGE_NOT_RUN | generate-allconcepts-manifest did not run (or its report was not copied), so this study's outputs were not published |

### EXCLUDED (1)

Stage the missing input(s), then re-run the study with STUDY_ID (or mark it not ready).

| Study | Abv | Stage | Code | Detail |
|---|---|---|---|---|
| phs000004 | FOUR | resolve-studies | MISSING_INPUTS | no decoded data CSVs in s3://b/r/phs000004/decoded_data/; no concept mapping at s3://b/r/phs000004/mappings/mapping2.csv |

### NOT_REACHED (4)

The pipeline stopped before this study was handled: fix the failure above and re-run.

| Study | Abv | Stage | Code | Detail |
|---|---|---|---|---|
| phs000001 | ONE | merge-allconcepts | STAGE_NOT_RUN | merge-allconcepts did not run (or its report was not copied), so this study's outputs were not published |
| phs000001 | ONE | generate-allconcepts-manifest | STAGE_NOT_RUN | generate-allconcepts-manifest did not run (or its report was not copied), so this study's outputs were not published |
| phs000002 | TWO | sstr-populate-rds-participants | NO_NEW_PARTICIPANTS | 0 new participants: a reload? |
| phs000002 | TWO | merge-allconcepts | STAGE_NOT_RUN | merge-allconcepts did not run (or its report was not copied), so this study's outputs were not published |
| phs000002 | TWO | generate-allconcepts-manifest | STAGE_NOT_RUN | generate-allconcepts-manifest did not run (or its report was not copied), so this study's outputs were not published |
| phs000005 | FIVE | create-vcf-indexes | NO_VCF_FILES | phs000005 has no VCF files |
| phs000005 | FIVE | merge-allconcepts | STAGE_NOT_RUN | merge-allconcepts did not run (or its report was not copied), so this study's outputs were not published |
| phs000005 | FIVE | generate-allconcepts-manifest | STAGE_NOT_RUN | generate-allconcepts-manifest did not run (or its report was not copied), so this study's outputs were not published |
| phs000006 | SIX | all-concepts-data-generator | NOT_STARTED | not started: the stage stopped first (a failure with CONTINUE_ON_STUDY_FAILURE off, an abort, or the timeout) |
| phs000006 | SIX | merge-allconcepts | STAGE_NOT_RUN | merge-allconcepts did not run (or its report was not copied), so this study's outputs were not published |
| phs000006 | SIX | generate-allconcepts-manifest | STAGE_NOT_RUN | generate-allconcepts-manifest did not run (or its report was not copied), so this study's outputs were not published |

## All studies

| Study | Abv | Status | SSTR | Participants | allConcepts | Consent groups | Decoded bytes |
|---|---|---|---|---|---|---|---|
| phs000001 | ONE | NOT_REACHED | SUCCESS | 120 | SUCCESS | 2 | 2147483648 |
| phs000002 | TWO | NOT_REACHED | UNSTABLE | 40 | SUCCESS | 1 | 1000 |
| phs000003 | THREE | FAILED | SUCCESS | 7 | FAILURE | 0 | 5000 |
| phs000004 | FOUR | EXCLUDED |  |  |  |  |  |
| phs000005 | FIVE | NOT_REACHED |  |  |  |  |  |
| phs000006 | SIX | NOT_REACHED | SUCCESS | 3 | NOT_RUN |  | 10 |
