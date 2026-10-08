package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.allconcepts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver.IoWriter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ExitCode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.report.ReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeAllConceptsJobTest {

    private static final String ROOT = "s3://bucket/root/";

    @TempDir
    Path tempDir;

    private IoResolver io;
    private MergeAllConceptsJob job;
    private JobExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        io = mock(IoResolver.class);
        when(io.isS3(anyString())).thenReturn(true);
        job = new MergeAllConceptsJob(io, mock(S3Client.class));

        EtlProperties properties = new EtlProperties();
        properties.getReports().setDir(tempDir.resolve("reports").toString());
        Files.createDirectories(tempDir.resolve("reports"));
        executor = new JobExecutor(
                new ReportWriter(new ObjectMapper().registerModule(new JavaTimeModule())), properties);
    }

    @Test
    void merged_file_name_comes_from_the_study_and_consent_folders() {
        assertThat(MergeAllConceptsJob.buildMergedFileName("phs000001/allConcepts/c2/"))
                .isEqualTo("phs000001_c2_allConcepts_MERGED.csv");
    }

    @Test
    void merges_only_consent_folders_under_each_study_allConcepts_folder() {
        when(io.listDirectoryNames(ROOT)).thenReturn(List.of("general", "phs000001", "phs000002"));
        when(io.listFilesRecursive(ROOT + "phs000001/allConcepts/")).thenReturn(List.of(
                "c1/phs000001_allConcepts_c1.csv",
                "c1/phs000001_c1_allConcepts_MERGED.csv",
                "c2/phs000001_allConcepts_c2.csv",
                "c2/nested/phs000001_allConcepts_c2.csv",
                "notes/phs000001_allConcepts_c1.csv"));

        JobResult result = executor.run(job, Map.of("input", ROOT), "test-merge");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(result.getMetrics()).containsEntry("consentFoldersDiscovered", 2L);
        verify(io).writeOutput(eq(ROOT + "phs000001/allConcepts/c1/phs000001_c1_allConcepts_MERGED.csv"),
                any(IoWriter.class));
        verify(io).writeOutput(eq(ROOT + "phs000001/allConcepts/c2/phs000001_c2_allConcepts_MERGED.csv"),
                any(IoWriter.class));
        verify(io, times(2)).writeOutput(anyString(), any(IoWriter.class));
        // Never the study folder as a whole: that would also pick up legacy/allConcepts/.
        verify(io, never()).listFilesRecursive(ROOT);
        verify(io, never()).listFilesRecursive(ROOT + "phs000001/");
        verify(io, never()).listFilesRecursive(ROOT + "general/allConcepts/");
    }

    @Test
    void study_filter_skips_discovering_study_folders() {
        when(io.listFilesRecursive(ROOT + "phs000002/allConcepts/"))
                .thenReturn(List.of("c1/phs000002_allConcepts_c1.csv"));

        JobResult result = executor.run(job,
                Map.of("input", ROOT, "study-ids", "phs000002"), "test-merge-filter");

        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS);
        verify(io, never()).listDirectoryNames(anyString());
        verify(io).writeOutput(eq(ROOT + "phs000002/allConcepts/c1/phs000002_c1_allConcepts_MERGED.csv"),
                any(IoWriter.class));
    }
}
