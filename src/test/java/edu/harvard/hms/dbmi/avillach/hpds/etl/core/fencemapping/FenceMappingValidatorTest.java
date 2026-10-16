package edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import org.junit.jupiter.api.Test;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.file;
import static edu.harvard.hms.dbmi.avillach.hpds.etl.support.FenceMappingFixtures.legacyEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FenceMappingValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FenceMappingValidator validator = new FenceMappingValidator(MAPPER);

    private FenceMappingValidator.Result validate(ObjectNode... entries) {
        return validator.validate(file(entries));
    }

    private static List<String> codes(FenceMappingValidator.Result r) {
        return r.report().getIssues().stream().map(ValidationIssue::code).toList();
    }

    @Test
    void a_legacy_shaped_file_passes_and_lists_public_entries() {
        FenceMappingValidator.Result r = validate(
                legacyEntry("phs123456", "c1", "parent", "/programs/phs123456.c1"),
                legacyEntry("phs123456", "c2", "parent", "/programs/phs123456.c2"),
                legacyEntry("phs999999", "", "public", "/programs/phs999999"));

        assertThat(r.hasErrors()).as(r.report().getIssues().toString()).isFalse();
        assertThat(r.keys()).containsExactlyInAnyOrder("phs123456.c1", "phs123456.c2", "phs999999");
        assertThat(r.report().getIssues())
                .anyMatch(i -> i.code().equals("FENCE_PUBLIC_ENTRIES") && i.message().contains("phs999999"));
    }

    @Test
    void null_optional_strings_are_accepted_because_psama_accepts_them() {
        ObjectNode e = legacyEntry("phs123456", "c1", "parent", "/programs/phs123456.c1");
        e.putNull("study_focus");
        e.putNull("is_harmonized");
        e.putNull("consent_group_code");

        assertThat(validate(e).hasErrors()).isFalse();
        assertThat(validate(e).keys()).containsExactly("phs123456");
    }

    @Test
    void an_unknown_field_is_an_error_because_psama_rejects_the_whole_file() {
        ObjectNode e = legacyEntry("phs123456", "c1", "parent", "/a");
        e.put("study_nickname", "oops");

        FenceMappingValidator.Result r = validate(e);

        assertThat(r.hasErrors()).isTrue();
        assertThat(r.report().getIssues()).anyMatch(i -> i.code().equals("FENCE_SCHEMA")
                && i.message().contains("study_nickname") && "/bio_data_catalyst/0".equals(i.location()));
    }

    @Test
    void an_unknown_root_property_is_an_error() {
        ObjectNode root = (ObjectNode) readTree(file(legacyEntry("phs123456", "c1", "parent", "/a")));
        root.put("version", 2);

        assertThat(validator.validate(root).hasErrors()).isTrue();
    }

    @Test
    void a_missing_bio_data_catalyst_array_is_an_error() {
        assertThat(validator.validate("{}").hasErrors()).isTrue();
    }

    @Test
    void a_missing_authZ_is_an_error() {
        ObjectNode e = legacyEntry("phs123456", "c1", "parent", "/a");
        e.remove("authZ");

        assertThat(validate(e).report().getIssues())
                .anyMatch(i -> i.code().equals("FENCE_SCHEMA") && i.message().contains("authZ"));
    }

    @Test
    void a_null_authZ_is_an_error_because_psama_throws_at_startup() {
        ObjectNode e = legacyEntry("phs123456", "c1", "parent", "/a");
        e.putNull("authZ");

        assertThat(validate(e).hasErrors()).isTrue();
    }

    @Test
    void a_blank_study_identifier_is_an_error() {
        assertThat(validate(legacyEntry("", "c1", "parent", "/a")).hasErrors()).isTrue();
    }

    @Test
    void a_non_integer_count_is_an_error() {
        ObjectNode e = legacyEntry("phs123456", "c1", "parent", "/a");
        e.put("clinical_variable_count", "many");
        ObjectNode fractional = legacyEntry("phs999999", "c1", "parent", "/b");
        fractional.put("genetic_sample_size", 1.5);

        FenceMappingValidator.Result r = validate(e, fractional);

        assertThat(r.report().getIssues().stream().filter(i -> i.code().equals("FENCE_SCHEMA"))).hasSize(2);
    }

    @Test
    void consent_codes_must_look_like_c_followed_by_digits_or_be_empty() {
        for (String bad : List.of("1", "C1", "c1a", "c", "c 1")) {
            assertThat(validate(legacyEntry("phs123456", bad, "parent", "/a")).hasErrors())
                    .as("consent_group_code '%s'", bad).isTrue();
        }
        for (String good : List.of("c1", "c12", "")) {
            assertThat(validate(legacyEntry("phs123456", good, "parent", "/a")).hasErrors())
                    .as("consent_group_code '%s'", good).isFalse();
        }
    }

    @Test
    void duplicate_keys_are_an_error_naming_both_positions() {
        FenceMappingValidator.Result r = validate(
                legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs123456", "c1", "parent", "/b"));

        assertThat(r.report().getIssues()).anyMatch(i -> i.code().equals("FENCE_DUPLICATE_KEY")
                && i.message().contains("phs123456.c1") && i.message().contains("[0]") && i.message().contains("[1]"));
    }

    @Test
    void a_bare_study_key_and_an_empty_code_collide() {
        FenceMappingValidator.Result r = validate(
                legacyEntry("phs123456", "", "public", "/a"),
                legacyEntry("phs123456", "", "public", "/b"));

        assertThat(codes(r)).contains("FENCE_DUPLICATE_KEY");
    }

    @Test
    void duplicate_authZ_is_an_error_after_psamas_slash_normalisation() {
        FenceMappingValidator.Result r = validate(
                legacyEntry("phs123456", "c1", "parent", "/programs/x"),
                legacyEntry("phs123456", "c2", "parent", "\\/programs/x"));

        assertThat(codes(r)).contains("FENCE_DUPLICATE_AUTHZ");
    }

    @Test
    void invalid_json_is_reported_not_thrown() {
        FenceMappingValidator.Result r = validator.validate("{ not json");

        assertThat(codes(r)).containsExactly("FENCE_JSON_INVALID");
    }

    @Test
    void coverage_reports_partitions_without_an_entry_and_entries_without_a_partition() {
        FenceMappingValidator.Result r = validate(
                legacyEntry("phs123456", "c1", "parent", "/a"),
                legacyEntry("phs123456", "c2", "parent", "/b"),
                legacyEntry("phs999999", "c1", "parent", "/c"));

        FenceMappingValidator.Coverage c = validator.coverage(r, List.of("phs123456.c1", "phs123456.c3", "DCC_Data"));

        assertThat(c.partitionsWithoutEntry()).containsExactly("DCC_Data", "phs123456.c3");
        assertThat(c.entriesWithoutPartition()).containsExactly("phs123456.c2", "phs999999.c1");
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
