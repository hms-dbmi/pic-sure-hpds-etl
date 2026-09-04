package edu.harvard.hms.dbmi.avillach.hpds.etl.config;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds an {@link S3Client} that operates as an explicitly assumed IAM role, for jobs
 * whose data lives in a bucket the environment's default principal cannot read (e.g. the
 * NHLBI harmonized-data exchange bucket). The role ARN arrives as a job parameter, so
 * this is a factory rather than a singleton client bean.
 */
public interface AssumedRoleS3Clients {

    S3Client create(String roleArn);
}
