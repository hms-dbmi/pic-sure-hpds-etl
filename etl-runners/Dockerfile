# One image for every hpds-etl job.
#
# The JAR already selects its job at runtime (--job=<name>), so there is nothing
# job-specific to bake in: one image serves both the migration and the permanent
# pipelines, and the image digest is the same artifact both pipelines validated.
#
# Build from the REPOSITORY ROOT so target/hpds-etl.jar is in context:
#   ./mvnw -B clean package
#   docker build --platform linux/amd64 -f etl-runners/Dockerfile -t hpds-etl-runner .
#
# The JAR is built on the Jenkins agent rather than in a builder stage here: the agent
# already has JDK 25 and the Maven cache (it runs the unit and Testcontainers suites in
# the same build), so the image the runner executes is the exact artifact the tests ran
# against, and no Maven Central access is needed from the ephemeral instance.

# Amazon Corretto 25 (LTS) matches the pom's <java.version>25>. Overridable so a base
# image bump is a build argument, not an edit.
ARG JAVA_IMAGE=amazoncorretto:25
FROM ${JAVA_IMAGE}

# jq is not needed inside the container -- the runner host does all AWS work, and the JAR
# reads s3:// inputs through the AWS SDK using the instance role via IMDS.

WORKDIR /app

COPY target/hpds-etl.jar /app/hpds-etl.jar
COPY etl-runners/run-job.sh /app/run-job.sh
RUN chmod +x /app/run-job.sh

# Reports land on a bind mount the host syncs to S3 after the run.
ENV ETL_REPORTS_DIR=/reports
RUN mkdir -p /reports

ENTRYPOINT ["/app/run-job.sh"]
