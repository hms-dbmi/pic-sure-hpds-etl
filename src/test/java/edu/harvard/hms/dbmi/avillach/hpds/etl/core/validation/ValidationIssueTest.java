package edu.harvard.hms.dbmi.avillach.hpds.etl.core.validation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ValidationIssue}'s context is an unmodifiable, null-free copy. */
class ValidationIssueTest {

    @Test
    void null_context_values_are_dropped_instead_of_throwing() {
        Map<String, Object> context = new HashMap<>();
        context.put("expected", "UUID");
        context.put("actual", null);

        ValidationIssue issue = ValidationIssue.warning("BAD_ID", "bad id", context);

        assertThat(issue.context()).containsExactly(Map.entry("expected", "UUID"));
    }

    @Test
    void a_null_context_becomes_empty() {
        assertThat(new ValidationIssue(Severity.INFO, "C", "m", null, null).context()).isEmpty();
    }

    @Test
    void the_context_is_a_detached_unmodifiable_copy_in_insertion_order() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("b", 1);
        context.put("a", 2);

        ValidationIssue issue = ValidationIssue.error("C", "m", "row 1", context);
        context.put("c", 3);

        assertThat(issue.context().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> issue.context().put("d", 4)).isInstanceOf(UnsupportedOperationException.class);
    }
}
