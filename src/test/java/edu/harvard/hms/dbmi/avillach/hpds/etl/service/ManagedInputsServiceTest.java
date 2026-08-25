package edu.harvard.hms.dbmi.avillach.hpds.etl.service;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ManagedInputsService}: what a row means, what caching guarantees,
 * and how an unconfigured or unreadable source fails.
 *
 * <p>{@code service/managed_inputs.csv} is a real excerpt of the BDC "Managed Inputs" sheet --
 * its full 25-column header, a study marked ready and one that is not, plus a row with no
 * abbreviated name. Reading the real header (rather than a hand-written three-column one) is
 * the point: it is the shape guaranteed in every run.
 */
class ManagedInputsServiceTest {

    private static final String STANDARD_HEADER =
            "Study Abbreviated Name,Study Identifier,Data is ready to process\n";

    private static ManagedInputsService service(IoResolver io, String uri) {
        EtlProperties properties = new EtlProperties();
        properties.getManagedInputs().setUri(uri);
        return new ManagedInputsService(io, new DelimitedReader(), properties);
    }

    /** A service whose properties can still be re-pointed after construction. */
    private static ManagedInputsService service(IoResolver io, EtlProperties properties) {
        return new ManagedInputsService(io, new DelimitedReader(), properties);
    }

    private static String sampleSheetPath() {
        URL url = ManagedInputsServiceTest.class.getResource("/service/managed_inputs.csv");
        assertThat(url).as("test resource service/managed_inputs.csv").isNotNull();
        return Path.of(url.getPath()).toString();
    }

    private static String tempCsv(String content) {
        try {
            Path file = Files.createTempDirectory("managed-inputs").resolve("managed_inputs.csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An IoResolver that hands back the given content, fresh, on every openInput call. */
    private static IoResolver ioReturning(String content) {
        IoResolver io = mock(IoResolver.class);
        when(io.openInput(any())).thenAnswer(
                invocation -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return io;
    }

    @Test
    void reads_every_study_from_a_sheet_with_the_standard_header() {
        List<ManagedInputRow> rows = service(new IoResolver(null), sampleSheetPath()).read();

        // The blank-abbreviation row names no study, so it is dropped rather than processed.
        assertThat(rows).containsExactly(
                new ManagedInputRow("PVDOMICS", "phs002451",
                        "v1", "p1", "No", "", "",
                        "NHLBI TOPMed: Pulmonary Vascular Disease Phenomics Program (PVDOMICS)",
                        "TOPMed", "TOPMed", "P/G", "No", "Yes", "No", "", "", "",
                        "Use dbGaP to submit a Data Access Request (DAR). For more information on how to request access to this dataset, please view the BDC Gitbook documentation: https://bdcatalyst.gitbook.io/biodata-catalyst-documentation/written-documentation/data-access/submitting-a-dbgap-data-access-request",
                        "", "", "topmed", "PVDOMICS", "Human", true, false),
                new ManagedInputRow("HeartShare-Harmonized-dataset", "phs004460",
                        "v1", "p1", "", "", "",
                        "HeartShare Harmonized dataset",
                        "", "", "", "", "", "",
                        "https://www.ncbi.nlm.nih.gov/projects/gap/cgi-bin/study.cgi?study_id=phs004460",
                        "", "",
                        "Use dbGaP to submit a Data Access Request (DAR). For more information on how to request access to this dataset, please view the BDC Gitbook documentation: [https://bdcatalyst.gitbook.io/biodata-catalyst-documentation/written-documentation/data-access/submitting-a-dbgap-data-access-request|https://bdcatalyst.gitbook.io/biodata-catalyst-documentation/written-documentation/data-access/submitting-a-dbgap-data-access-request]",
                        "", "", "", "HeartShare_Harmonized", "Human", false, false));
    }

    /** Not-ready studies are returned too; filtering them is the caller's decision, not the read's. */
    @Test
    void returns_studies_that_are_not_ready_alongside_ready_ones() {
        List<ManagedInputRow> rows = service(new IoResolver(null), sampleSheetPath()).read();

        assertThat(rows).filteredOn(ManagedInputRow::isReady).hasSize(1);
        assertThat(rows).hasSize(2);
    }

    @Test
    void reads_yes_and_no_as_ready_values() {
        String csv = STANDARD_HEADER
                + "YES1,phs000001,Yes\n"
                + "YES2,phs000002, yes \n"
                + "YES3,phs000003,YES\n"
                + "NO1,phs000004,No\n"
                + "NO2,phs000005,no\n"
                + "NO3,phs000006,\n";

        List<ManagedInputRow> rows = service(new IoResolver(null), tempCsv(csv)).read();

        assertThat(rows).extracting(ManagedInputRow::abv, ManagedInputRow::isReady)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("YES1", true),
                        org.assertj.core.groups.Tuple.tuple("YES2", true),
                        org.assertj.core.groups.Tuple.tuple("YES3", true),
                        org.assertj.core.groups.Tuple.tuple("NO1", false),
                        org.assertj.core.groups.Tuple.tuple("NO2", false),
                        org.assertj.core.groups.Tuple.tuple("NO3", false));
    }

    @Test
    void rejects_invalid_values_in_ready_column() {
        String csv = STANDARD_HEADER + "BAD,phs000001,true\n";

        assertThatThrownBy(() -> service(new IoResolver(null), tempCsv(csv)).read())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("true")
                .hasMessageContaining("Data is ready to process")
                .hasMessageContaining("Yes")
                .hasMessageContaining("No");
    }

    @Test
    void skips_rows_missing_an_abbreviation_or_a_study_identifier() {
        String csv = STANDARD_HEADER
                + "GOOD,phs000001,Yes\n"
                + ",phs000002,Yes\n"
                + "NOID,,Yes\n"
                + "  ,  ,Yes\n";

        assertThat(service(new IoResolver(null), tempCsv(csv)).read())
                .containsExactly(ManagedInputRow.of("GOOD", "phs000001", true));
    }

    @Test
    void trims_surrounding_whitespace_from_names_and_identifiers() {
        String csv = STANDARD_HEADER + "  SPACED  ,  phs000009 ,Yes\n";

        assertThat(service(new IoResolver(null), tempCsv(csv)).read())
                .containsExactly(ManagedInputRow.of("SPACED", "phs000009", true));
    }

    /** The reason the service exists: a job may call read() per study without re-reading the source. */
    @Test
    void reads_the_source_once_however_often_read_is_called() {
        IoResolver io = ioReturning(STANDARD_HEADER + "ONE,phs000001,Yes\n");
        ManagedInputsService service = service(io, "s3://bucket/managed_inputs.csv");

        List<ManagedInputRow> first = service.read();
        List<ManagedInputRow> second = service.read();
        service.read();

        verify(io, times(1)).openInput("s3://bucket/managed_inputs.csv");
        assertThat(second).isSameAs(first);
    }

    @Test
    void re_reads_when_the_configured_source_changes() {
        IoResolver io = ioReturning(STANDARD_HEADER + "ONE,phs000001,Yes\n");
        EtlProperties properties = new EtlProperties();
        properties.getManagedInputs().setUri("s3://bucket/first.csv");
        ManagedInputsService service = service(io, properties);

        service.read();
        properties.getManagedInputs().setUri("s3://bucket/second.csv");
        service.read();

        verify(io).openInput("s3://bucket/first.csv");
        verify(io).openInput("s3://bucket/second.csv");
    }

    @Test
    void returns_a_list_callers_cannot_mutate() {
        List<ManagedInputRow> rows = service(new IoResolver(null), sampleSheetPath()).read();

        assertThatThrownBy(() -> rows.add(ManagedInputRow.of("SNEAKY", "phs000000", true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * An unconfigured source must not read as "no studies": that would migrate nothing and
     * report success.
     */
    @Test
    void fails_with_a_config_error_when_no_source_is_configured() {
        assertThatThrownBy(() -> service(new IoResolver(null), "   ").read())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("No managed inputs source is configured");
    }

    @Test
    void fails_with_a_config_error_when_the_configured_source_does_not_exist() {
        assertThatThrownBy(() -> service(new IoResolver(null), "/no/such/file/managed_inputs.csv").read())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Input file not found");
    }
}
