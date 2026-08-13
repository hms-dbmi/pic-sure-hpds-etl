package edu.harvard.hms.dbmi.avillach.hpds.etl.core.job;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JobContext#getBoolean} must not use {@link Boolean#parseBoolean}, which maps every
 * unrecognised string — a typo such as {@code treu} included — to {@code false}, leaving a job to
 * do the opposite of what was asked and still report success.
 */
class JobContextTest {

    private static JobContext context(Map<String, String> params) {
        return new JobContext("a-job", "run-1", Path.of("target"), params);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", " true ", "yes", "Y", "1", "on", "ON"})
    void accepts_recognised_true_literals(String raw) {
        assertThat(context(Map.of("flag", raw)).getBoolean("flag", false)).isTrue();
        assertThat(JobContext.isBooleanLiteral(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False", " false ", "no", "N", "0", "off", "OFF"})
    void accepts_recognised_false_literals(String raw) {
        assertThat(context(Map.of("flag", raw)).getBoolean("flag", true)).isFalse();
        assertThat(JobContext.isBooleanLiteral(raw)).isTrue();
    }

    /** A misspelt flag is a misconfiguration, not a silent false. */
    @ParameterizedTest
    @ValueSource(strings = {"treu", "ture", "tru", "flase", "t", "f", "2", "enabled", "null"})
    void rejects_anything_that_is_not_a_boolean_literal(String raw) {
        JobContext ctx = context(Map.of("flag", raw));

        assertThatThrownBy(() -> ctx.getBoolean("flag", false))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("--flag")
                .hasMessageContaining(raw);

        assertThat(JobContext.isBooleanLiteral(raw)).isFalse();
    }

    @Test
    void a_rejected_boolean_maps_to_the_config_error_exit_code() {
        JobContext ctx = context(Map.of("flag", "treu"));

        assertThatThrownBy(() -> ctx.getBoolean("flag", false))
                .isInstanceOf(ConfigException.class)
                .extracting(e -> ((ConfigException) e).exitCode())
                .isEqualTo(ExitCode.CONFIG_ERROR);
    }

    @Test
    void uses_the_default_when_the_parameter_is_absent_or_blank() {
        assertThat(context(Map.of()).getBoolean("flag", true)).isTrue();
        assertThat(context(Map.of()).getBoolean("flag", false)).isFalse();
        // get() treats blank as absent, so a blank value falls back rather than failing.
        assertThat(context(Map.of("flag", "   ")).getBoolean("flag", true)).isTrue();
    }

    @Test
    void isBooleanLiteral_rejects_null_and_blank() {
        assertThat(JobContext.isBooleanLiteral(null)).isFalse();
        assertThat(JobContext.isBooleanLiteral("")).isFalse();
        assertThat(JobContext.isBooleanLiteral("  ")).isFalse();
    }
}
