package edu.harvard.hms.dbmi.avillach.hpds.etl.core.io;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URI parsing rules for {@link IoResolver.S3Uri}: object addressing ({@code parse})
 * requires a key, while prefix listings ({@code parsePrefix}) legally address the
 * bucket root — the case that broke the first bucket-root listing in production.
 */
class IoResolverS3UriTest {

    @Test
    void parse_requires_bucket_and_key() {
        assertThat(IoResolver.S3Uri.parse("s3://bucket/some/key"))
                .isEqualTo(new IoResolver.S3Uri("bucket", "some/key"));
        assertThatThrownBy(() -> IoResolver.S3Uri.parse("s3://bucket"))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> IoResolver.S3Uri.parse("s3://bucket/"))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void parsePrefix_accepts_the_bucket_root() {
        assertThat(IoResolver.S3Uri.parsePrefix("s3://bucket"))
                .isEqualTo(new IoResolver.S3Uri("bucket", ""));
        assertThat(IoResolver.S3Uri.parsePrefix("s3://bucket/"))
                .isEqualTo(new IoResolver.S3Uri("bucket", ""));
        assertThat(IoResolver.S3Uri.parsePrefix("s3://bucket/pre/fix/"))
                .isEqualTo(new IoResolver.S3Uri("bucket", "pre/fix/"));
        assertThatThrownBy(() -> IoResolver.S3Uri.parsePrefix("s3:///key"))
                .isInstanceOf(ConfigException.class);
    }
}
