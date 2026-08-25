# One image for every hpds-etl job. The JAR selects its job at runtime (--job=<name>), so a single
# image serves both pipelines and the digest they validated is the digest that runs.
#
# Build from the REPOSITORY ROOT so target/hpds-etl.jar is in context:
#   ./mvnw -B clean package
#   docker build --platform linux/amd64 -f etl-runners/Dockerfile -t hpds-etl-runner .
#
# The JAR is built on the Jenkins agent rather than in a builder stage: the agent already has
# JDK 25 and the Maven cache, the image therefore runs the exact artifact the tests ran against,
# and the ephemeral instance needs no Maven Central access.

# Amazon Corretto 25 (LTS) matches <java.version> in the pom. Overridable so a base image bump is
# a build argument rather than an edit.
ARG JAVA_IMAGE=amazoncorretto:25
FROM ${JAVA_IMAGE}

# No AWS CLI or jq needed: the host does all AWS work, and the JAR reads s3:// inputs through the
# AWS SDK using the instance role via IMDS.

WORKDIR /app

COPY target/hpds-etl.jar /app/hpds-etl.jar
COPY etl-runners/run-job.sh /app/run-job.sh
RUN chmod +x /app/run-job.sh

# Reports land on a bind mount the host syncs to S3 after the run.
ENV ETL_REPORTS_DIR=/reports
RUN mkdir -p /reports

ENTRYPOINT ["/app/run-job.sh"]
