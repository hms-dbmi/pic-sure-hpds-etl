package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.harmonized;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.support.JobTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GenerateIdentityConsentMappingJob}. Pure — no Spring context, no
 * network: the "pull" is mocked by pointing {@code --base} at a local directory tree shaped
 * exactly like the S3 drop, which exercises the same {@link IoResolver} code paths the S3 run
 * uses (discovery, recursive search, streaming). The assumed-role factory is a stub that
 * fails the test if it is ever touched, proving local runs never build an S3 client.
 */
class GenerateIdentityConsentMappingJobTest {

    private final GenerateIdentityConsentMappingJob job = new GenerateIdentityConsentMappingJob(
            new IoResolver(null), new DelimitedReader(),
            roleArn -> { throw new AssertionError("assumed-role client must not be built for local runs"); },
            new ObjectMapper());

    @TempDir
    Path tmp;

    private void personTsv(String dataset, String studyDir, String group, String content) throws IOException {
        Path dir = tmp.resolve(dataset).resolve(studyDir).resolve("consent_groups")
                .resolve(group).resolve(group + "_BDCHM").resolve("mapped-data");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Person.tsv"), content);
    }

    private JobResult run(Map<String, String> extra) throws Exception {
        Map<String, String> params = new HashMap<>(Map.of(
                "base", tmp.toString(),
                "output", tmp.resolve("out").toString()));
        params.putAll(extra);
        return job.run(JobTestSupport.context(job.name(), params));
    }

    private String outputFile(String name) throws IOException {
        return Files.readString(tmp.resolve("out").resolve(name));
    }

    @Test
    void maps_identities_from_every_consent_group_of_the_latest_dataset() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260101", "DMC_OLD",
                "parent-old-phs000009-v1-r1-c1", "id\tidentity\nu0\tSTALE\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\nu2\t222\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_Y",
                "parent-y_HMB_-phs000002-v2-p1-c2", "id\tidentity\nu3\t333\n");

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("consentGroups", 2L)
                .containsEntry("rows", 3L)
                .containsEntry("crossConsentIdentities", 0L);
        assertThat(outputFile("identity_consent_mapping.csv")).isEqualTo(
                "Person.Identity,study_id,consent_code\n"
                        + "111,phs000001,c1\n"
                        + "222,phs000001,c1\n"
                        + "333,phs000002,c2\n");
    }

    @Test
    void explicit_dataset_prefix_overrides_latest_selection() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260101", "DMC_OLD",
                "parent-old-phs000009-v1-r1-c1", "id\tidentity\nu0\t900\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\n");

        JobResult result = run(Map.of("dataset-prefix", "BDC-DMC-Harmonization-Examples-20260101"));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outputFile("identity_consent_mapping.csv")).contains("900,phs000009,c1");
    }

    @Test
    void per_study_splits_output_by_accession() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_Y",
                "parent-y_HMB_-phs000002-v2-p1-c2", "id\tidentity\nu3\t333\n");

        JobResult result = run(Map.of("per-study", "true"));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outputFile("identity_consent_mapping_phs000001.csv")).contains("111,phs000001,c1");
        assertThat(outputFile("identity_consent_mapping_phs000002.csv")).contains("333,phs000002,c2");
    }

    @Test
    void warns_when_an_identity_spans_consent_groups_of_one_study() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c2", "id\tidentity\nu2\t111\n");

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getMetrics()).containsEntry("crossConsentIdentities", 1L);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("CROSS_CONSENT_IDENTITY"));
    }

    @Test
    void counts_blank_identities_and_dedupes_within_a_group() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\nu2\t\nu3\t111\n");

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics())
                .containsEntry("rows", 1L)
                .containsEntry("blankIdentityRows", 1L)
                .containsEntry("inGroupDuplicates", 1L);
    }

    @Test
    void tolerates_json_encoded_identity_values() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1",
                "id\tidentity\nu1\t['123']\nu2\t[{'system': 'dbGaP', 'value': '456'}]\n");

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outputFile("identity_consent_mapping.csv")).isEqualTo(
                "Person.Identity,study_id,consent_code\n"
                        + "123,phs000001,c1\n"
                        + "456,phs000001,c1\n");
    }

    @Test
    void skips_unparseable_group_names_and_studies_without_consent_groups() throws Exception {
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "parent-x-phs000001-v1-r1-c1", "id\tidentity\nu1\t111\n");
        personTsv("BDC-DMC-Harmonization-Examples-20260202", "DMC_X",
                "no-accession-or-consent-here", "id\tidentity\nu9\t999\n");
        Files.createDirectories(tmp.resolve("BDC-DMC-Harmonization-Examples-20260202")
                .resolve("DMC_EMPTY"));

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("rows", 1L);
        assertThat(outputFile("identity_consent_mapping.csv")).doesNotContain("999");
    }

    @Test
    void fails_with_config_error_when_no_dataset_exists() throws Exception {
        Files.createDirectories(tmp.resolve("unrelated-prefix"));

        // ConfigException propagates out of run(); JobExecutor maps it to exit 5.
        assertThatThrownBy(() -> run(Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("No BDC-DMC-Harmonization-Examples");
    }

    @Test
    void fails_output_validation_when_dataset_yields_no_rows() throws Exception {
        Files.createDirectories(tmp.resolve("BDC-DMC-Harmonization-Examples-20260202")
                .resolve("DMC_EMPTY"));

        JobResult result = run(Map.of());

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.code().equals("EMPTY_MAPPING"));
    }

    @Test
    void s3_base_requires_a_role_arn() throws Exception {
        JobResult result = job.run(JobTestSupport.context(job.name(),
                Map.of("base", "s3://some-bucket", "output", tmp.resolve("out").toString())));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("MISSING_ROLE"));
    }

    @Test
    void rejects_a_bad_per_study_flag() throws Exception {
        JobResult result = run(Map.of("per-study", "maybe"));

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> i.code().equals("BAD_PER_STUDY"));
    }
}
