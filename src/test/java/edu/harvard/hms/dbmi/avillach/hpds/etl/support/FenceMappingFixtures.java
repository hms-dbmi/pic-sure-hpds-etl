package edu.harvard.hms.dbmi.avillach.hpds.etl.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds fence_mapping.json fixtures. Callers must use only phs123456 / phs999999 / phs000000. */
public final class FenceMappingFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FenceMappingFixtures() {
    }

    /** All 23 fields in the shape the legacy generator (BDCMetadataElements) writes: counts -1, strings never null. */
    public static ObjectNode legacyEntry(String study, String code, String studyType, String authZ) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("study_identifier", study);
        n.put("study_type", studyType);
        n.put("abbreviated_name", "TEST");
        n.put("full_study_name", "Test study");
        n.put("consent_group_code", code);
        n.put("consent_group_name_abv", "GRU");
        n.put("consent_group_name", "General Research Use");
        n.put("request_access", "https://example.org/?study_id=" + study);
        n.put("data_type", "Genotype");
        for (String count : List.of("clinical_variable_count", "genetic_sample_size", "clinical_sample_size",
                "raw_clinical_variable_count", "raw_genetic_sample_size", "raw_clinical_sample_size")) {
            n.put(count, -1);
        }
        n.put("study_version", "v1");
        n.put("study_phase", "p1");
        n.put("top_level_path", "\\" + study + "\\");
        n.put("is_harmonized", "N");
        n.put("study_focus", "");
        n.put("study_design", "");
        n.put("authZ", authZ);
        n.put("additional_information", "");
        return n;
    }

    public static String file(ObjectNode... entries) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("bio_data_catalyst");
        for (ObjectNode e : entries) {
            array.add(e);
        }
        return root.toString();
    }
}
