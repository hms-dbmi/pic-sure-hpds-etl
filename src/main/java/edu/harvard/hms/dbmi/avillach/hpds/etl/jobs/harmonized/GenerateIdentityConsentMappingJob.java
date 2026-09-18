package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.harmonized;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.config.AssumedRoleS3Clients;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.DelimitedReader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.io.IoResolver;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.AbstractJob;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobContext;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobExpectations;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.JobType;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.job.ParamSpec;
import edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation.ValidationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates the harmonized-data consent mapping file (ALS-12727): one row of
 * {@code Person.Identity,study_id,consent_code} per identity value found in each consent
 * group's {@code Person.tsv} of a DMC harmonization drop.
 *
 * <p>The drop is partitioned by consent group and each group's directory name carries the
 * study accession and consent code (e.g. {@code nih-nhlbi-topmed-parent-aric-phs000280-v8-r1-c1}),
 * so the mapping is derivable from the drop alone: the path supplies {@code study_id} and
 * {@code consent_code}; {@code mapped-data/Person.tsv} supplies the dbGaP identities.
 *
 * <p>With no {@code --dataset-prefix}, the newest {@code BDC-DMC-Harmonization-Examples-YYYYMMDD}
 * prefix under {@code --base} is selected deterministically. The source bucket is readable only
 * by the NHLBI exchange role, passed as {@code --role-arn}; all access under it is read-only.
 * The output goes through the default credentials, so the CSVs land where the Jenkins job can
 * archive them regardless of the input role.
 */
@Component
@ConditionalOnProperty(name = "etl.jobs.generate-identity-consent-mapping.enabled", havingValue = "true")
public class GenerateIdentityConsentMappingJob
        extends AbstractJob<GenerateIdentityConsentMappingJob.Output> {

    private static final Pattern DATASET = Pattern.compile("BDC-DMC-Harmonization-Examples-(\\d{8})");
    private static final Pattern STUDY = Pattern.compile("phs(\\d{6})");
    private static final Pattern CONSENT = Pattern.compile("[-_](c\\d+)$");
    private static final String PERSON_TSV_SUFFIX = "mapped-data/Person.tsv";
    private static final String OUTPUT_HEADER = "Person.Identity,study_id,consent_code";

    private final IoResolver io;
    private final DelimitedReader delimitedReader;
    private final AssumedRoleS3Clients assumedRoleClients;
    private final ObjectMapper mapper;

    public GenerateIdentityConsentMappingJob(IoResolver io, DelimitedReader delimitedReader,
                                             AssumedRoleS3Clients assumedRoleClients,
                                             ObjectMapper mapper) {
        this.io = io;
        this.delimitedReader = delimitedReader;
        this.assumedRoleClients = assumedRoleClients;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "generate-identity-consent-mapping";
    }

    @Override
    public JobType type() {
        return JobType.PERMANENT;
    }

    @Override
    public JobExpectations expectations() {
        return JobExpectations.of(
                List.of(
                        ParamSpec.optional("base", "Location holding the DMC harmonization drops (s3:// URI or local dir)",
                                "s3://nih-nhlbi-bdc-harmdata-exchange"),
                        ParamSpec.optional("dataset-prefix",
                                "Drop to ingest; blank selects the newest BDC-DMC-Harmonization-Examples-YYYYMMDD deterministically",
                                "BDC-DMC-Harmonization-Examples-20260804"),
                        ParamSpec.optional("role-arn",
                                "IAM role assumed for all reads of --base (required for the NHLBI exchange bucket)",
                                "arn:aws:iam::714862078411:role/nih-nhlbi-TopMed-EC2Access-S3"),
                        ParamSpec.optional("output", "Directory (URI or local path) the mapping CSV(s) are written to", "output"),
                        ParamSpec.optional("per-study", "true = one CSV per study instead of a single combined file", "false")),
                List.of("identity_consent_mapping.csv (or identity_consent_mapping_<phs>.csv per study) at --output"));
    }

    @Override
    protected void validateInput(JobContext ctx, ValidationReport report) {
        String perStudy = ctx.get("per-study", "false");
        if (!perStudy.equals("true") && !perStudy.equals("false")) {
            report.error("BAD_PER_STUDY", "per-study must be 'true' or 'false', got: " + perStudy, "--per-study");
        }
        String base = ctx.get("base", "s3://nih-nhlbi-bdc-harmdata-exchange");
        if (io.isS3(base) && ctx.get("role-arn").isEmpty()) {
            report.error("MISSING_ROLE", "an s3:// base requires --role-arn (the exchange bucket is not "
                    + "readable by the default principal)", "--role-arn");
        }
    }

    @Override
    protected Output execute(JobContext ctx) {
        String base = trimTrailingSlash(ctx.get("base", "s3://nih-nhlbi-bdc-harmdata-exchange"));
        boolean perStudy = ctx.get("per-study", "false").equals("true");
        String outputDir = trimTrailingSlash(ctx.get("output", "output"));

        // Input may need the exchange role; output always goes through default credentials.
        IoResolver input = ctx.get("role-arn")
                .map(arn -> new IoResolver(assumedRoleClients.create(arn)))
                .orElse(io);

        String dataset = resolveDataset(input, base, ctx.get("dataset-prefix").orElse(""));
        log.info("Reading dataset {} under {}", dataset, base);

        Set<Row> rows = new TreeSet<>();
        Map<String, Set<String>> consentsPerIdentity = new HashMap<>(); // study|identity -> consents
        long blankIdentityRows = 0;
        long inGroupDuplicates = 0;
        int groups = 0;

        for (String studyDir : input.listDirectoryNames(base + "/" + dataset)) {
            String consentGroupsUri = base + "/" + dataset + "/" + studyDir + "/consent_groups";
            List<String> groupNames = input.listDirectoryNames(consentGroupsUri);
            if (groupNames.isEmpty()) {
                log.warn("{}: no consent_groups/ prefix, skipped", studyDir);
                continue;
            }
            for (String groupName : groupNames) {
                GroupId id = parseGroupName(groupName);
                if (id == null) {
                    log.warn("Unparseable consent-group name skipped: {}", groupName);
                    continue;
                }
                for (String relative : input.listFilesRecursive(consentGroupsUri + "/" + groupName)) {
                    if (!relative.endsWith(PERSON_TSV_SUFFIX)) {
                        continue;
                    }
                    groups++;
                    Set<Row> groupRows = new HashSet<>();
                    String tsvUri = consentGroupsUri + "/" + groupName + "/" + relative;
                    InputStream in = input.openInput(tsvUri);
                    try (Stream<Map<String, String>> stream = delimitedReader.stream(in, DelimitedReader.TAB)) {
                        for (Map<String, String> row : (Iterable<Map<String, String>>) stream::iterator) {
                            List<String> identities = parseIdentity(identityColumn(row));
                            if (identities.isEmpty()) {
                                blankIdentityRows++;
                                continue;
                            }
                            for (String identity : identities) {
                                Row mapping = new Row(identity, id.studyId(), id.consentCode());
                                if (!groupRows.add(mapping)) {
                                    inGroupDuplicates++;
                                }
                                consentsPerIdentity
                                        .computeIfAbsent(id.studyId() + "|" + identity, k -> new HashSet<>())
                                        .add(id.consentCode());
                            }
                        }
                    }
                    rows.addAll(groupRows);
                    log.info("Group {}/{}: {} mapping row(s)", id.studyId(), id.consentCode(), groupRows.size());
                }
            }
        }

        long crossConsentIdentities = consentsPerIdentity.values().stream()
                .filter(consents -> consents.size() > 1).count();

        List<String> written = write(rows, perStudy, outputDir);
        return new Output(dataset, groups, rows.size(), blankIdentityRows,
                inGroupDuplicates, crossConsentIdentities, written);
    }

    @Override
    protected void validateOutput(Output output, JobContext ctx, ValidationReport report) {
        if (output.rows() == 0) {
            report.error("EMPTY_MAPPING", "No mapping rows were produced from dataset " + output.dataset());
        }
        if (output.crossConsentIdentities() > 0) {
            report.warning("CROSS_CONSENT_IDENTITY", output.crossConsentIdentities()
                    + " identity(ies) appear in more than one consent group of the same study — "
                    + "likely a source-data issue worth raising with the DMC");
        }
    }

    @Override
    protected void report(Output output, JobResult.Builder builder) {
        builder.metric("consentGroups", (long) output.consentGroups())
                .metric("rows", (long) output.rows())
                .metric("blankIdentityRows", output.blankIdentityRows())
                .metric("inGroupDuplicates", output.inGroupDuplicates())
                .metric("crossConsentIdentities", output.crossConsentIdentities());
    }

    private String resolveDataset(IoResolver input, String base, String requested) {
        if (!requested.isBlank()) {
            return trimTrailingSlash(requested);
        }
        return input.listDirectoryNames(base).stream()
                .filter(name -> DATASET.matcher(name).matches())
                .max(String::compareTo)
                .orElseThrow(() -> new ConfigException(
                        "No BDC-DMC-Harmonization-Examples-YYYYMMDD prefix found under " + base));
    }

    private List<String> write(Set<Row> rows, boolean perStudy, String outputDir) {
        Map<String, Set<Row>> files = new TreeMap<>();
        if (perStudy) {
            for (Row row : rows) {
                files.computeIfAbsent("identity_consent_mapping_" + row.studyId() + ".csv",
                        k -> new TreeSet<>()).add(row);
            }
        } else {
            files.put("identity_consent_mapping.csv", rows);
        }
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, Set<Row>> file : files.entrySet()) {
            String uri = outputDir + "/" + file.getKey();
            io.writeOutput(uri, out -> {
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(out, StandardCharsets.UTF_8));
                writer.write(OUTPUT_HEADER);
                writer.newLine();
                for (Row row : file.getValue()) {
                    writer.write(row.identity() + "," + row.studyId() + "," + row.consentCode());
                    writer.newLine();
                }
                writer.flush();
            });
            written.add(uri);
            log.info("Wrote {} row(s) to {}", file.getValue().size(), uri);
        }
        return written;
    }

    private static String identityColumn(Map<String, String> row) {
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().strip().equalsIgnoreCase("identity")) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    /**
     * A bdchm {@code Person.identity} is normally a bare dbGaP subject id, but tolerate the
     * JSON-ish list/object encodings some exports use. An object contributes its {@code value}
     * field when present, otherwise all of its field values.
     */
    List<String> parseIdentity(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return List.of();
        }
        if (!value.startsWith("[")) {
            return List.of(value);
        }
        try {
            JsonNode parsed = mapper.readTree(value.replace('\'', '"'));
            List<String> out = new ArrayList<>();
            for (JsonNode item : parsed.isArray() ? parsed : mapper.createArrayNode().add(parsed)) {
                if (item.isObject()) {
                    JsonNode valueField = valueField(item);
                    if (valueField != null) {
                        addIfPresent(out, valueField.asText());
                    } else {
                        item.forEach(field -> addIfPresent(out, field.asText()));
                    }
                } else {
                    addIfPresent(out, item.asText());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            String stripped = value.replaceAll("[\\[\\]{}'\", ]", " ");
            return Stream.of(stripped.split("\\s+")).filter(s -> !s.isBlank()).toList();
        }
    }

    private static JsonNode valueField(JsonNode object) {
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (field.strip().equalsIgnoreCase("value")) {
                return object.get(field);
            }
        }
        return null;
    }

    private static void addIfPresent(List<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.strip());
        }
    }

    static GroupId parseGroupName(String name) {
        Matcher study = STUDY.matcher(name);
        Matcher consent = CONSENT.matcher(name);
        if (!study.find() || !consent.find()) {
            return null;
        }
        return new GroupId("phs" + study.group(1), consent.group(1));
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record GroupId(String studyId, String consentCode) {
    }

    record Row(String identity, String studyId, String consentCode) implements Comparable<Row> {
        @Override
        public int compareTo(Row other) {
            int c = identity.compareTo(other.identity);
            if (c != 0) {
                return c;
            }
            c = studyId.compareTo(other.studyId);
            return c != 0 ? c : consentCode.compareTo(other.consentCode);
        }
    }

    /** Immutable result of {@link #execute}; inspected by {@link #validateOutput}/{@link #report}. */
    public record Output(String dataset, int consentGroups, int rows, long blankIdentityRows,
                         long inGroupDuplicates, long crossConsentIdentities, List<String> written) {
    }
}
