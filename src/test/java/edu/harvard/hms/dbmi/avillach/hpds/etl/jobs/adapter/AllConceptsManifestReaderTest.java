package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.ManifestFormatException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.ManifestRow;
import edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.adapter.AllConceptsManifestReader.Plan;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllConceptsManifestReaderTest {

    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    private static final String BASE = "s3://b/data/allConcepts_manifest.csv";

    private static List<ManifestRow> read(String csv) throws IOException {
        return AllConceptsManifestReader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static String manifest(String... partitions) {
        StringBuilder b = new StringBuilder(AllConceptsManifestReader.HEADER).append('\n');
        b.append("global,s3://b/data/global_AllConcepts.csv,v1,2026-01-01T00:00:00Z,").append(MD5).append('\n');
        for (String p : partitions) {
            b.append(p).append(",s3://b/data/").append(p).append(".csv,v7,2026-01-01T00:00:00Z,").append(MD5).append('\n');
        }
        return b.toString();
    }

    private static ValidationReport plan(String csv, Set<String> studies, Set<String> codes, Plan[] out) throws IOException {
        ValidationReport report = new ValidationReport("input");
        out[0] = AllConceptsManifestReader.plan(read(csv), BASE, studies, codes, report);
        return report;
    }

    private static List<String> codes(ValidationReport r) {
        return r.getIssues().stream().map(ValidationIssue::code).toList();
    }

    @Test
    void reads_the_five_columns_and_ignores_extra_trailing_ones_and_quoting() throws IOException {
        List<ManifestRow> rows = read("partition,s3_uri,version_id,last_updated,md5,rows,excluded\n"
                + "global,\"s3://b/a,b.csv\",v1,2026-01-01T00:00:00Z," + MD5 + ",5,0\n"
                + "phs000000.c1,s3://b/x.csv,v2,2026-01-01T00:00:00Z," + MD5 + ",9,1\n\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).s3Uri()).isEqualTo("s3://b/a,b.csv");
        assertThat(rows.get(1)).isEqualTo(new ManifestRow("phs000000.c1", "s3://b/x.csv", "v2", "2026-01-01T00:00:00Z", MD5));
    }

    @Test
    void a_wrong_header_or_a_short_row_is_a_format_error() {
        assertThatThrownBy(() -> read("partition,uri\nglobal,x\n")).isInstanceOf(ManifestFormatException.class);
        assertThatThrownBy(() -> read(AllConceptsManifestReader.HEADER + "\nglobal,x,y\n")).isInstanceOf(ManifestFormatException.class);
        assertThatThrownBy(() -> read("")).isInstanceOf(ManifestFormatException.class);
    }

    @Test
    void a_good_manifest_plans_sorted_partitions_with_studies_and_lists_every_code() throws IOException {
        Plan[] p = new Plan[1];
        ValidationReport r = plan(manifest("phs999999.c1", "phs000000.c2", "phs000000.c1"), Set.of(), Set.of(), p);
        assertThat(r.hasErrors()).isFalse();
        assertThat(p[0].partitions).extracting(AllConceptsManifestReader.PartitionSpec::name)
                .containsExactly("phs000000.c1", "phs000000.c2", "phs999999.c1");
        assertThat(p[0].studies).containsExactly("phs000000", "phs999999");
        assertThat(p[0].codesSeen).containsExactly("1", "2");
        assertThat(p[0].partitions.get(2).studyIndex()).isEqualTo(1);
        assertThat(p[0].global.partition()).isEqualTo("global");
    }

    @Test
    void the_global_row_is_required() throws IOException {
        Plan[] p = new Plan[1];
        ValidationReport r = plan(AllConceptsManifestReader.HEADER + "\nphs000000.c1,s3://b/x,v1,t," + MD5 + "\n", Set.of(), Set.of(), p);
        assertThat(codes(r)).contains("GLOBAL_ROW_MISSING");
    }

    @Test
    void codes_that_are_not_positive_integers_are_refused_unless_allowed() throws IOException {
        Plan[] p = new Plan[1];
        assertThat(codes(plan(manifest("phs000000.c0"), Set.of(), Set.of(), p))).containsExactly("CONSENT_CODE_NOT_ALLOWED");
        assertThat(codes(plan(manifest("phs000000.cpublic"), Set.of(), Set.of(), p))).containsExactly("CONSENT_CODE_NOT_ALLOWED");
        assertThat(codes(plan(manifest("phs000000.c01"), Set.of(), Set.of(), p))).containsExactly("CONSENT_CODE_NOT_ALLOWED");
        ValidationReport allowed = plan(manifest("phs000000.c0", "phs000000.cpublic"), Set.of(), Set.of("0", "public"), p);
        assertThat(allowed.hasErrors()).isFalse();
        assertThat(p[0].codesSeen).containsExactly("0", "public");
    }

    @Test
    void a_consent_code_outside_the_token_set_is_refused_even_when_listed() throws IOException {
        Plan[] p = new Plan[1];
        assertThat(codes(plan(manifest("phs000000.c1.5"), Set.of(), Set.of("1.5"), p))).contains("BAD_CONSENT_CODE");
        assertThat(codes(plan(manifest("phs000000.c"), Set.of(), Set.of(), p))).contains("BAD_PARTITION_NAME");
    }

    @Test
    void names_the_runner_would_refuse_are_refused_here() throws IOException {
        Plan[] p = new Plan[1];
        assertThat(codes(plan(manifest("phs000000-x.c1"), Set.of(), Set.of(), p))).containsExactly("HYPHENATED_PARTITION");
        assertThat(codes(plan(manifest("phs000000..x.c1"), Set.of(), Set.of(), p))).containsExactly("BAD_PARTITION_NAME");
        assertThat(codes(plan(manifest(".phs000000.c1"), Set.of(), Set.of(), p))).containsExactly("BAD_PARTITION_NAME");
        assertThat(codes(plan(manifest("phs000000"), Set.of(), Set.of(), p))).containsExactly("BAD_PARTITION_NAME");
    }

    @Test
    void duplicate_partitions_are_refused_without_regard_to_case() throws IOException {
        Plan[] p = new Plan[1];
        assertThat(codes(plan(manifest("phs000000.c1", "PHS000000.c1"), Set.of(), Set.of(), p))).contains("DUPLICATE_PARTITION");
    }

    @Test
    void s3_rows_need_a_version_and_an_md5() throws IOException {
        Plan[] p = new Plan[1];
        String csv = AllConceptsManifestReader.HEADER + "\n"
                + "global,s3://b/g,,t," + MD5 + "\n"
                + "phs000000.c1,s3://b/x,v1,t,notmd5\n";
        assertThat(codes(plan(csv, Set.of(), Set.of(), p))).containsExactlyInAnyOrder("MANIFEST_VERSION_MISSING", "MANIFEST_MD5_MISSING");
    }

    @Test
    void studies_filter_leaves_other_studies_out_and_unknown_studies_are_refused() throws IOException {
        Plan[] p = new Plan[1];
        ValidationReport r = plan(manifest("phs000000.c1", "phs999999.c1", "phs999999.c0"), Set.of("phs000000"), Set.of(), p);
        assertThat(r.hasErrors()).isFalse(); // phs999999.c0 is out of scope, so its code is not judged
        assertThat(p[0].partitions).extracting(AllConceptsManifestReader.PartitionSpec::name).containsExactly("phs000000.c1");
        assertThat(p[0].excludedPartitions).containsExactly("phs999999.c0", "phs999999.c1");
        assertThat(p[0].excludedStudies).containsExactly("phs999999");
        assertThat(p[0].codesSeen).containsExactly("0", "1");
        assertThat(codes(plan(manifest("phs000000.c1"), Set.of("phs123456"), Set.of(), p))).contains("STUDY_NOT_IN_MANIFEST");
    }

    @Test
    void relative_local_paths_are_taken_from_the_manifest_directory() {
        assertThat(AllConceptsManifestReader.resolve("/data/in/allConcepts_manifest.csv", "g.csv")).isEqualTo("/data/in/g.csv");
        assertThat(AllConceptsManifestReader.resolve("/data/in/m.csv", "s3://b/k")).isEqualTo("s3://b/k");
        assertThat(AllConceptsManifestReader.resolve("/data/in/m.csv", "/abs/x.csv")).isEqualTo("/abs/x.csv");
    }
}
