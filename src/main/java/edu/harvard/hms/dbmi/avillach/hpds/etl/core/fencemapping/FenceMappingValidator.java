package edu.harvard.hms.dbmi.avillach.hpds.etl.core.fencemapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationIssue;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates a PSAMA {@code fence_mapping.json}. PSAMA itself offers no validation (it logs a
 * swallowed parse error and starts with an empty mapping), so this is the only gate before the
 * file is shipped. See {@code docs/FENCE_MAPPING.md} for each rule and what it protects against.
 *
 * <p>Two layers:
 * <ol>
 *   <li>the JSON Schema {@code schemas/fence_mapping.schema.json} (structure, types, unknown
 *       fields, required fields, consent code shape);</li>
 *   <li>coded checks for what a schema cannot express: unique map key
 *       ({@code study_identifier[.consent_group_code]}), unique {@code authZ}, and the list of
 *       {@code public} entries (granted to every user).</li>
 * </ol>
 * Partition coverage against a set of HPDS partition names is a separate call,
 * {@link #coverage}, because only some callers have partitions.
 */
@Component
public class FenceMappingValidator {

    public static final String SCHEMA_RESOURCE = "/schemas/fence_mapping.schema.json";

    /** Cap on schema-error issues so a wholly wrong file does not produce a megabyte report. */
    static final int MAX_SCHEMA_ISSUES = 100;

    private final ObjectMapper mapper;
    private final Schema schema;

    public FenceMappingValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream in = FenceMappingValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource missing from classpath: " + SCHEMA_RESOURCE);
            }
            this.schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One entry of {@code bio_data_catalyst} reduced to the fields PSAMA actually uses. */
    public record Entry(int index, String key, String studyIdentifier, String consentGroupCode,
                        String authZ, boolean isPublic) {
    }

    /** The findings plus the entries that could be interpreted, for the coverage cross-check. */
    public record Result(ValidationReport report, List<Entry> entries) {

        public boolean hasErrors() {
            return report.hasErrors();
        }

        /** PSAMA map keys, e.g. {@code phs123456.c1}. */
        public Set<String> keys() {
            Set<String> keys = new LinkedHashSet<>();
            entries.forEach(e -> keys.add(e.key()));
            return keys;
        }
    }

    /** Partitions with no matching entry (unreachable) and entries with no partition. */
    public record Coverage(List<String> partitionsWithoutEntry, List<String> entriesWithoutPartition) {
    }

    public Result validate(InputStream in) {
        JsonNode root;
        try (in) {
            root = mapper.readTree(in);
        } catch (IOException e) {
            return parseFailure(e);
        }
        return validate(root);
    }

    public Result validate(String json) {
        try {
            return validate(mapper.readTree(json));
        } catch (IOException e) {
            return parseFailure(e);
        }
    }

    public Result validate(JsonNode root) {
        ValidationReport report = new ValidationReport("fence-mapping");
        if (root == null || root.isMissingNode() || root.isNull()) {
            report.error("FENCE_JSON_INVALID", "fence_mapping is empty or JSON null");
            return new Result(report, List.of());
        }

        List<Error> errors = schema.validate(root);
        int shown = 0;
        for (Error e : errors) {
            if (shown++ == MAX_SCHEMA_ISSUES) {
                report.error("FENCE_SCHEMA", (errors.size() - MAX_SCHEMA_ISSUES) + " more schema error(s) not shown");
                break;
            }
            report.error("FENCE_SCHEMA", e.getMessage(), e.getInstanceLocation().toString());
        }

        List<Entry> entries = collectEntries(root);
        codedChecks(entries, report);
        return new Result(report, entries);
    }

    /** Which partitions have no fence-mapping entry, and which entries have no partition. */
    public Coverage coverage(Result result, Collection<String> partitions) {
        Set<String> keys = result.keys();
        Set<String> partitionSet = new TreeSet<>(partitions);
        List<String> noEntry = partitionSet.stream().filter(p -> !keys.contains(p)).toList();
        List<String> noPartition = keys.stream().filter(k -> !partitionSet.contains(k)).sorted().toList();
        return new Coverage(noEntry, noPartition);
    }

    private Result parseFailure(IOException e) {
        ValidationReport report = new ValidationReport("fence-mapping");
        report.add(ValidationIssue.error("FENCE_JSON_INVALID", "Not valid JSON: " + e.getMessage()));
        return new Result(report, List.of());
    }

    /** Mirrors the key/authZ derivation in PSAMA's FenceMappingUtility:57-62. */
    private static List<Entry> collectEntries(JsonNode root) {
        List<Entry> entries = new ArrayList<>();
        JsonNode array = root.get("bio_data_catalyst");
        if (array == null || !array.isArray()) {
            return entries;
        }
        int index = 0;
        for (JsonNode item : array) {
            int i = index++;
            if (!item.isObject() || !item.path("study_identifier").isTextual()
                    || !item.path("authZ").isTextual()) {
                continue; // already reported by the schema
            }
            String study = item.get("study_identifier").asText();
            String code = item.path("consent_group_code").isTextual()
                    ? item.get("consent_group_code").asText() : "";
            String key = code.isEmpty() ? study : study + "." + code;
            String authZ = item.get("authZ").asText().replace("\\/", "/");
            boolean isPublic = item.path("study_type").isTextual()
                    && "public".equalsIgnoreCase(item.get("study_type").asText());
            entries.add(new Entry(i, key, study, code, authZ, isPublic));
        }
        return entries;
    }

    private static void codedChecks(List<Entry> entries, ValidationReport report) {
        Map<String, Integer> firstByKey = new LinkedHashMap<>();
        Map<String, Integer> firstByAuthZ = new LinkedHashMap<>();
        List<String> publicKeys = new ArrayList<>();
        for (Entry e : entries) {
            Integer prior = firstByKey.putIfAbsent(e.key(), e.index());
            if (prior != null) {
                report.error("FENCE_DUPLICATE_KEY",
                        "Key '" + e.key() + "' appears at bio_data_catalyst[" + prior + "] and ["
                                + e.index() + "]; PSAMA keeps only one and drops the other silently",
                        "/bio_data_catalyst/" + e.index());
            }
            prior = firstByAuthZ.putIfAbsent(e.authZ(), e.index());
            if (prior != null) {
                report.error("FENCE_DUPLICATE_AUTHZ",
                        "authZ '" + e.authZ() + "' appears at bio_data_catalyst[" + prior + "] and ["
                                + e.index() + "]; a FENCE login resolves to only one of them",
                        "/bio_data_catalyst/" + e.index());
            }
            if (e.isPublic()) {
                publicKeys.add(e.key());
            }
        }
        if (!publicKeys.isEmpty()) {
            report.info("FENCE_PUBLIC_ENTRIES", publicKeys.size()
                    + " entr" + (publicKeys.size() == 1 ? "y has" : "ies have")
                    + " study_type=public and are granted to every user: " + publicKeys);
        }
    }
}
