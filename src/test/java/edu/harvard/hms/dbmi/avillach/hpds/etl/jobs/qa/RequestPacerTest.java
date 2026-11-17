package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPacerTest {

    private final ManualClock clock = new ManualClock();
    private final List<Duration> waits = new ArrayList<>();

    private RequestPacer pacer(int spacingSeconds) {
        return new RequestPacer(Duration.ofSeconds(spacingSeconds), clock, d -> {
            waits.add(d);
            clock.advance(d);
        });
    }

    /** One request: wait for its turn, take {@code seconds}, end overloaded or not. */
    private void request(RequestPacer p, int seconds, boolean overloaded) throws InterruptedException {
        p.awaitTurn();
        clock.advance(Duration.ofSeconds(seconds));
        p.finished(overloaded);
    }

    @Test
    void the_first_request_goes_at_once_and_the_next_waits_the_gap_after_the_previous_one_ends()
            throws InterruptedException {
        RequestPacer p = pacer(2);

        request(p, 13, false);
        request(p, 13, false);

        // A 13 s answer, then a 2 s gap: spacing counts from the end, not only the start.
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void requests_that_start_together_are_spread_out() throws InterruptedException {
        RequestPacer p = pacer(2);

        p.awaitTurn();
        p.awaitTurn();
        p.awaitTurn();

        // Each claims the next slot: 0 s, then 2 s after the first, then 2 s after that.
        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void an_overloaded_answer_doubles_the_gap_up_to_the_cap_and_answers_narrow_it_back()
            throws InterruptedException {
        RequestPacer p = pacer(2);

        for (int i = 0; i < 7; i++) {
            request(p, 1, true);
        }
        assertThat(p.currentSpacing()).isEqualTo(RequestPacer.MAX_SPACING);

        request(p, 1, false);
        assertThat(p.currentSpacing()).isEqualTo(Duration.ofSeconds(30));
        for (int i = 0; i < 10; i++) {
            request(p, 1, false);
        }
        assertThat(p.currentSpacing()).isEqualTo(Duration.ofSeconds(2));
        // 4, 8, 16, 32, 60, 60 s after the overloaded answers (the first had nothing before it).
        assertThat(waits.subList(0, 6)).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(32), Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    @Test
    void a_gap_already_passed_costs_nothing() throws InterruptedException {
        RequestPacer p = pacer(2);

        request(p, 1, false);
        clock.advance(Duration.ofSeconds(10));
        request(p, 1, false);

        assertThat(waits).isEmpty();
    }

    @Test
    void zero_spacing_turns_pacing_off_overloads_included() throws InterruptedException {
        RequestPacer p = pacer(0);

        for (int i = 0; i < 5; i++) {
            request(p, 0, true);
        }

        assertThat(waits).isEmpty();
        assertThat(p.currentSpacing()).isEqualTo(Duration.ZERO);
    }
}
