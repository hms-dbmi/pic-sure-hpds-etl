package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed binding of the {@code etl.*} configuration tree (see application.yml).
 */
@ConfigurationProperties(prefix = "etl")
public class EtlProperties {

    private Reports reports = new Reports();

    @NestedConfigurationProperty
    private Aws aws = new Aws();

    /**
     * Named, ordered lists of job names for the in-process pipeline runner
     * ({@code --pipeline=<name>}). Prefer Jenkins stages for production chaining; this
     * is primarily for local and CI runs. Example:
     * <pre>
     * etl.pipelines.migrate-all: [participants-migration, consents-migration]
     * </pre>
     */
    private Map<String, List<String>> pipelines = new LinkedHashMap<>();

    @NestedConfigurationProperty
    private ManagedInputs managedInputs = new ManagedInputs();

    public Reports getReports() { return reports; }
    public void setReports(Reports reports) { this.reports = reports; }
    public Aws getAws() { return aws; }
    public void setAws(Aws aws) { this.aws = aws; }
    public Map<String, List<String>> getPipelines() { return pipelines; }
    public void setPipelines(Map<String, List<String>> pipelines) { this.pipelines = pipelines; }
    public ManagedInputs getManagedInputs() { return managedInputs; }
    public void setManagedInputs(ManagedInputs managedInputs) { this.managedInputs = managedInputs; }

    public static class Reports {
        /** Directory where per-run JSON reports are written and archived by Jenkins. */
        private String dir = "./reports";
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class Aws {
        private String region = "us-east-1";
        private S3 s3 = new S3();
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public S3 getS3() { return s3; }
        public void setS3(S3 s3) { this.s3 = s3; }

        public static class S3 {
            /**
             * Overrides the S3 endpoint. Blank in prod (real S3); set to a LocalStack
             * URL in tests. When set, path-style access is enabled automatically.
             */
            private String endpointOverride = "";
            public String getEndpointOverride() { return endpointOverride; }
            public void setEndpointOverride(String endpointOverride) { this.endpointOverride = endpointOverride; }
        }
    }

    public static class ManagedInputs {
        private String uri;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }
}
