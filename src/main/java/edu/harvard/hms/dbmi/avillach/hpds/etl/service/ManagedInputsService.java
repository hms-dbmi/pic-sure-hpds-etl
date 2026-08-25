package edu.harvard.hms.dbmi.avillach.hpds.etl.service;

import edu.harvard.hms.dbmi.avillach.hpds.etl.config.EtlProperties;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The one place the study list ("managed inputs") is read, so jobs ask for studies rather
 * than parsing a CSV. Two things follow from that:
 *
 * <ul>
 *   <li>{@link #read()} is cached, so a job may call it as often as it likes -- per study,
 *       per validation step -- without re-reading the source.</li>
 *   <li>The source is configuration ({@code etl.managed-inputs.uri}), not a job parameter.
 *       Moving from a CSV to the Google sheet, a database, or a different bucket changes
 *       this class only; no job's CLI or code changes.</li>
 * </ul>
 *
 * <p>The cache is keyed by the configured location: re-pointing {@code etl.managed-inputs.uri}
 * invalidates it. Within one job run the location never changes, so the file is read once.
 */
@Component
public class ManagedInputsService {

    private static final Logger log = LoggerFactory.getLogger(ManagedInputsService.class);

    static final String COL_ABV = "Study Abbreviated Name";
    static final String COL_STUDY_ID = "Study Identifier";
    static final String COL_VERSION = "Version";
    static final String COL_PHASE = "Phase";
    static final String COL_VERSION_UPDATE = "Version Update";
    static final String COL_PREVIOUS_VERSION = "Previous Version";
    static final String COL_PREVIOUS_PHASE = "Previous Phase";
    static final String COL_STUDY_FULL_NAME = "Study Full Name";
    static final String COL_STUDY_TYPE = "Study Type";
    static final String COL_BDC_PROGRAMS = "BDC Program(s)";
    static final String COL_DATA_TYPE = "Data Type";
    static final String COL_DCC_HARMONIZED = "DCC Harmonized";
    static final String COL_HAS_MULTI = "Has Multi";
    static final String COL_USE_MANUAL_TABLE_METHODS = "Use Manual Table Methods";
    static final String COL_MORE_INFO_LINK = "More Info Link";
    static final String COL_ADDITIONAL_INFO_URL = "Additional Information Link (URL)";
    static final String COL_ADDITIONAL_INFO_LABEL = "Additional Information Link (Label)";
    static final String COL_REQUEST_ACCESS_TEXT = "Request Access Text";
    static final String COL_PHENO_INGEST_NHLBI_ACCOUNT = "Pheno Ingest NHLBI Account";
    static final String COL_PHENO_INGEST_BUCKETS = "Pheno Ingest buckets";
    static final String COL_GEN3_AUTHZ_PROGRAM_NAME = "Gen3 Authz Program Name";
    static final String COL_GEN3_AUTHZ_PROJECT_NAME = "Gen3 Authz Project Name";
    static final String COL_SUBJECT_TYPES = "Subject Type(s)";
    static final String COL_IS_READY = "Data is ready to process";
    static final String COL_DATA_PROCESSED = "Data Processed";

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final EtlProperties properties;

    /** The location {@link #cached} was read from; null until the first successful read. */
    private String cachedUri;
    private List<ManagedInputRow> cached;

    public ManagedInputsService(IoResolver io, DelimitedReader delimitedReader, EtlProperties properties) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.properties = properties;
    }

    /**
     * Every study in the managed inputs, in source order -- including studies that are not
     * ready to process, which callers filter on {@link ManagedInputRow#isReady()}.
     *
     * <p>Rows missing an abbreviated name or a study identifier are skipped and logged: they
     * name no study, so there is nothing to process.
     *
     * <p>Cached after the first call. Synchronized so parallel jobs sharing this bean read the
     * source once rather than racing on it.
     *
     * @throws ConfigException if no source is configured, or the configured source is missing
     */
    public synchronized List<ManagedInputRow> read() {
        String uri = configuredUri();
        if (cached != null && uri.equals(cachedUri)) {
            return cached;
        }
        List<ManagedInputRow> rows = load(uri);
        cachedUri = uri;
        cached = rows;
        log.info("Read {} managed input row(s) from {}", rows.size(), uri);
        return cached;
    }

    private String configuredUri() {
        String uri = properties.getManagedInputs().getUri();
        if (uri == null || uri.isBlank()) {
            throw new ConfigException("No managed inputs source is configured. Pass --managed-inputs=<local path "
                    + "or s3:// URI>, or set MANAGED_INPUTS_URI / etl.managed-inputs.uri");
        }
        return uri.trim();
    }

    private List<ManagedInputRow> load(String uri) {
        List<ManagedInputRow> rows = new ArrayList<>();
        InputStream in = io.openInput(uri);
        try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.COMMA)) {
            for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                String abv = Strings.trimToNull(row.get(COL_ABV));
                String studyId = Strings.trimToNull(row.get(COL_STUDY_ID));
                if (abv == null || studyId == null) {
                    log.warn("managed-inputs: skipping row with a blank '{}' or '{}': {}", COL_ABV, COL_STUDY_ID, row);
                    continue;
                }
                rows.add(new ManagedInputRow(
                        abv, studyId,
                        col(row, COL_VERSION),
                        col(row, COL_PHASE),
                        col(row, COL_VERSION_UPDATE),
                        col(row, COL_PREVIOUS_VERSION),
                        col(row, COL_PREVIOUS_PHASE),
                        col(row, COL_STUDY_FULL_NAME),
                        col(row, COL_STUDY_TYPE),
                        col(row, COL_BDC_PROGRAMS),
                        col(row, COL_DATA_TYPE),
                        col(row, COL_DCC_HARMONIZED),
                        col(row, COL_HAS_MULTI),
                        col(row, COL_USE_MANUAL_TABLE_METHODS),
                        col(row, COL_MORE_INFO_LINK),
                        col(row, COL_ADDITIONAL_INFO_URL),
                        col(row, COL_ADDITIONAL_INFO_LABEL),
                        col(row, COL_REQUEST_ACCESS_TEXT),
                        col(row, COL_PHENO_INGEST_NHLBI_ACCOUNT),
                        col(row, COL_PHENO_INGEST_BUCKETS),
                        col(row, COL_GEN3_AUTHZ_PROGRAM_NAME),
                        col(row, COL_GEN3_AUTHZ_PROJECT_NAME),
                        col(row, COL_SUBJECT_TYPES),
                        parseYesNo(row.get(COL_IS_READY), COL_IS_READY, studyId),
                        parseYesNo(row.get(COL_DATA_PROCESSED), COL_DATA_PROCESSED, studyId)));
            }
        }
        return List.copyOf(rows);
    }

    private static String col(Map<String, String> row, String column) {
        String v = row.getOrDefault(column, "");
        return v == null ? "" : v.trim();
    }

    private static boolean parseYesNo(String raw, String column, String studyId) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("yes")) {
            return true;
        }
        if (v.equalsIgnoreCase("no")) {
            return false;
        }
        throw new ConfigException("managed-inputs: study " + studyId + " has invalid value '"
                + v + "' in column '" + column + "'; expected 'Yes' or 'No'");
    }

}
