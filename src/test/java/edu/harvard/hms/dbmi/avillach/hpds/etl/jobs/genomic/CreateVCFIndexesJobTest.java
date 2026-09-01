package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.genomic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.service.ManagedInputsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateVCFIndexesJobTest {

    @TempDir
    Path tempDir;

    private ManagedInputsService managedInputsService;
    private ConsentRepository consentRepository;
    private SampleRepository sampleRepository;
    private IoResolver ioResolver;

    private CreateVCFIndexesJob job;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        managedInputsService = mock(ManagedInputsService.class);
        consentRepository = mock(ConsentRepository.class);
        sampleRepository = mock(SampleRepository.class);
        ioResolver = mock(IoResolver.class);

        job = new CreateVCFIndexesJob(managedInputsService, consentRepository, sampleRepository, ioResolver);

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    private static ManagedInputRow genomicStudy(String abv, String studyId) {
        return new ManagedInputRow(abv, studyId, "", "", "", "", "", "", "", "", "P/G", "", "", "", "", "", "", "", "", "", "", "", "", true, false);
    }

    private static ManagedInputRow nonGenomicStudy(String abv, String studyId) {
        return new ManagedInputRow(abv, studyId, "", "", "", "", "", "", "", "", "P", "", "", "", "", "", "", "", "", "", "", "", "", true, false);
    }

    private static ManagedInputRow processedGenomicStudy(String abv, String studyId) {
        return new ManagedInputRow(abv, studyId, "", "", "", "", "", "", "", "", "P/G", "", "", "", "", "", "", "", "", "", "", "", "", true, true);
    }

    @Test
    void generates_vcf_index_and_sample_ids_for_genomic_study() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String studyId = "phs000123";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU"),
                new Consent(uuid2, studyId, "2", "HMB")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "NWD100001", "TOPMed"),
                new Sample(uuid2, "NWD100002", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-happy");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ioResolver, org.mockito.Mockito.atLeast(4)).writeOutput(uriCaptor.capture(), contentCaptor.capture());

        List<String> uris = uriCaptor.getAllValues();
        assertThat(uris).contains(
                outputPath + "phs000123.c1_vcfIndex.tsv",
                outputPath + "phs000123.c1_SampleIds.csv",
                outputPath + "phs000123.c2_vcfIndex.tsv",
                outputPath + "phs000123.c2_SampleIds.csv");

        int vcfIdx = uris.indexOf(outputPath + "phs000123.c1_vcfIndex.tsv");
        String vcfContent = new String(contentCaptor.getAllValues().get(vcfIdx), StandardCharsets.UTF_8);
        assertThat(vcfContent).startsWith("phs000123.c1\n");
        assertThat(vcfContent).contains("vcf_path\tchromosome\tisAnnotated");
        assertThat(vcfContent).contains("NWD100001");
        assertThat(vcfContent).contains(uuid1.toString());

        int csvIdx = uris.indexOf(outputPath + "phs000123.c1_SampleIds.csv");
        String csvContent = new String(contentCaptor.getAllValues().get(csvIdx), StandardCharsets.UTF_8);
        assertThat(csvContent).contains(uuid1 + ",TST,NWD100001");
    }

    @Test
    void skips_non_genomic_studies() {
        UUID uuid1 = UUID.randomUUID();
        String genomicId = "phs000001";
        String phenoId = "phs000002";

        when(managedInputsService.read()).thenReturn(List.of(
                genomicStudy("GEN", genomicId),
                nonGenomicStudy("PHE", phenoId)));

        when(consentRepository.findByStudyId(genomicId)).thenReturn(List.of(
                new Consent(uuid1, genomicId, "1", "GRU")));
        when(sampleRepository.findByStudyId(genomicId)).thenReturn(List.of(
                new Sample(uuid1, "NWD200001", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-skip-pheno");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        verify(consentRepository, never()).findByStudyId(phenoId);
    }

    @Test
    void skips_already_processed_studies() {
        UUID uuid1 = UUID.randomUUID();
        String unprocessedId = "phs000001";
        String processedId = "phs000002";

        when(managedInputsService.read()).thenReturn(List.of(
                genomicStudy("NEW", unprocessedId),
                processedGenomicStudy("OLD", processedId)));

        when(consentRepository.findByStudyId(unprocessedId)).thenReturn(List.of(
                new Consent(uuid1, unprocessedId, "1", "GRU")));
        when(sampleRepository.findByStudyId(unprocessedId)).thenReturn(List.of(
                new Sample(uuid1, "NWD700001", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-skip-processed");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        verify(consentRepository, never()).findByStudyId(processedId);
    }

    @Test
    void excludes_c0_consent_group() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String studyId = "phs000456";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "0", "NONE"),
                new Consent(uuid2, studyId, "1", "GRU")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "NWD300001", "TOPMed"),
                new Sample(uuid2, "NWD300002", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-c0");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeast(2)).writeOutput(uriCaptor.capture(), any(byte[].class));

        List<String> uris = uriCaptor.getAllValues();
        assertThat(uris).noneMatch(u -> u.contains(".c0_"));
        assertThat(uris).anyMatch(u -> u.contains(".c1_"));
    }

    @Test
    void excludes_non_nwd_samples() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String studyId = "phs000789";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU"),
                new Consent(uuid2, studyId, "1", "GRU")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "NWD400001", "TOPMed"),
                new Sample(uuid2, "OTHER123", "Other")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-non-nwd");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeast(1)).writeOutput(uriCaptor.capture(), contentCaptor.capture());

        int vcfIdx = uriCaptor.getAllValues().indexOf(outputPath + "phs000789.c1_vcfIndex.tsv");
        String vcfContent = new String(contentCaptor.getAllValues().get(vcfIdx), StandardCharsets.UTF_8);
        assertThat(vcfContent).contains("NWD400001");
        assertThat(vcfContent).doesNotContain("OTHER123");
    }

    @Test
    void warns_and_succeeds_when_no_genomic_studies_found() {
        when(managedInputsService.read()).thenReturn(List.of(nonGenomicStudy("PHE", "phs000999")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-no-genomic");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getInputValidation().hasErrors()).isFalse();
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> "NO_GENOMIC_STUDIES".equals(i.code()));
    }

    @Test
    void skips_study_with_no_consents() {
        UUID uuid1 = UUID.randomUUID();
        String studyWithConsents = "phs000001";
        String studyNoConsents = "phs000002";

        when(managedInputsService.read()).thenReturn(List.of(
                genomicStudy("S1", studyWithConsents),
                genomicStudy("S2", studyNoConsents)));

        when(consentRepository.findByStudyId(studyWithConsents)).thenReturn(List.of(
                new Consent(uuid1, studyWithConsents, "1", "GRU")));
        when(consentRepository.findByStudyId(studyNoConsents)).thenReturn(List.of());

        when(sampleRepository.findByStudyId(studyWithConsents)).thenReturn(List.of(
                new Sample(uuid1, "NWD500001", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-no-consents");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getOutputValidation().getIssues())
                .anyMatch(i -> i.message().contains(studyNoConsents));
    }

    @Test
    void fails_when_only_genomic_study_has_no_samples() {
        String studyId = "phs000333";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(UUID.randomUUID(), studyId, "1", "GRU")));
        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of());

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-no-samples");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(result.getOutputValidation().hasErrors()).isTrue();
    }

    @Test
    void does_not_match_non_genomic_data_types_containing_letter_g() {
        when(managedInputsService.read()).thenReturn(List.of(
                new ManagedInputRow("IMG", "phs000444", "", "", "", "", "", "", "", "", "Imaging", "", "", "", "", "", "", "", "", "", "", "", "", true, false)));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-imaging");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS_WITH_WARNINGS);
        assertThat(result.getInputValidation().hasErrors()).isFalse();
        assertThat(result.getInputValidation().getIssues())
                .anyMatch(i -> "NO_GENOMIC_STUDIES".equals(i.code()));
    }

    @Test
    void vcf_index_has_23_chromosome_rows() {
        UUID uuid1 = UUID.randomUUID();
        String studyId = "phs000111";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU")));
        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "NWD600001", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-chroms");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeast(1)).writeOutput(uriCaptor.capture(), contentCaptor.capture());

        int vcfIdx = uriCaptor.getAllValues().indexOf(outputPath + "phs000111.c1_vcfIndex.tsv");
        String vcfContent = new String(contentCaptor.getAllValues().get(vcfIdx), StandardCharsets.UTF_8);
        String[] lines = vcfContent.split("\n");

        // Line 1: consent group name, Line 2: header, Lines 3-25: 23 chromosome rows
        assertThat(lines).hasSize(25);
        assertThat(lines[0]).isEqualTo("phs000111.c1");
        assertThat(lines[1]).startsWith("vcf_path\tchromosome");

        // Verify chromosome range
        for (int i = 1; i <= 22; i++) {
            assertThat(lines[i + 1]).contains("\t" + i + "\t");
        }
        assertThat(lines[24]).contains("\tX\t");

        // Verify VCF path format
        assertThat(lines[2]).contains("data/vcfInput/phs000111.c1.chr1.annotated_remove_modifiers.hpds.vcf.gz");
        assertThat(lines[24]).contains("data/vcfInput/phs000111.c1.chrX.annotated_remove_modifiers.hpds.vcf.gz");

        // Verify isAnnotated and isGzipped
        for (int i = 2; i < 25; i++) {
            String[] fields = lines[i].split("\t", -1);
            assertThat(fields[2]).isEqualTo("TRUE");
            assertThat(fields[3]).isEqualTo("TRUE");
            assertThat(fields[6]).isEmpty();
            assertThat(fields[7]).isEmpty();
        }
    }

    @Test
    void sample_ids_and_patient_ids_counts_match_on_every_row() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        UUID uuid3 = UUID.randomUUID();
        String studyId = "phs000222";

        when(managedInputsService.read()).thenReturn(List.of(genomicStudy("TST", studyId)));

        when(consentRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Consent(uuid1, studyId, "1", "GRU"),
                new Consent(uuid2, studyId, "1", "GRU"),
                new Consent(uuid3, studyId, "1", "GRU")));

        when(sampleRepository.findByStudyId(studyId)).thenReturn(List.of(
                new Sample(uuid1, "NWD800001", "TOPMed"),
                new Sample(uuid2, "NWD800002", "TOPMed"),
                new Sample(uuid3, "NWD800003", "TOPMed")));

        String outputPath = tempDir.resolve("output").toString() + "/";
        JobResult result = executor.run(job, Map.of("output", outputPath), "test-id-counts");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(ioResolver, org.mockito.Mockito.atLeast(1)).writeOutput(uriCaptor.capture(), contentCaptor.capture());

        int vcfIdx = uriCaptor.getAllValues().indexOf(outputPath + "phs000222.c1_vcfIndex.tsv");
        String vcfContent = new String(contentCaptor.getAllValues().get(vcfIdx), StandardCharsets.UTF_8);
        String[] lines = vcfContent.split("\n");

        for (int i = 2; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            String[] sampleIds = fields[4].split(",", -1);
            String[] patientIds = fields[5].split(",", -1);

            assertThat(sampleIds)
                    .as("row %d: sample_ids count must match patient_ids count", i)
                    .hasSameSizeAs(patientIds);
            assertThat(sampleIds).hasSize(3);

            for (String sid : sampleIds) {
                assertThat(sid).startsWith("NWD");
            }
            for (String pid : patientIds) {
                assertThat(pid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            }
        }
    }
}
