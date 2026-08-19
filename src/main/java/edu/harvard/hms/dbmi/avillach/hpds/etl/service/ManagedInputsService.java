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
    static final String COL_IS_READY = "Data is ready to process";

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
                rows.add(new ManagedInputRow(abv, studyId, parseReady(row.get(COL_IS_READY))));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The sheet's ready column is hand-maintained, so anything that is not an affirmative
     * ("Yes"/"true"/"1") -- including a blank cell or a note typed into it -- means not ready.
     * Processing a study nobody marked ready is the more expensive mistake.
     */
    private static boolean parseReady(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

}
