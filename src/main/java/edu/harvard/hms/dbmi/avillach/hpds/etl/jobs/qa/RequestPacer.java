package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps a gap between dbGaP requests, so the SSTR API (slow, and given to HTTP 502 when busy) is
 * never sent requests back to back. A request starts no sooner than {@code spacing} after the
 * previous request started <em>and</em> after the previous one finished, whichever is later, across
 * every thread of the run.
 *
 * <p>Adaptive: a request that finds dbGaP overloaded (429, 502, 503, 504 or a request timeout)
 * doubles the gap, up to {@link #MAX_SPACING}; each request that is answered halves it again,
 * back down to {@code spacing}. A spacing of zero turns pacing off, adaptive part included.
 *
 * <p>Thread-safe. The clock and the wait are injected, so tests never sleep.
 */
final class RequestPacer {

    /** The widest the gap grows while dbGaP keeps answering overloaded. */
    static final Duration MAX_SPACING = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(RequestPacer.class);

    private final Duration spacing;
    private final Clock clock;
    private final DbgapStudyClient.Sleeper sleeper;

    private Duration current;
    /** When the latest request started (or is due to start); null before the first. */
    private Instant lastStart;
    /** When the latest request finished; null before the first. */
    private Instant lastEnd;

    RequestPacer(Duration spacing, Clock clock, DbgapStudyClient.Sleeper sleeper) {
        this.spacing = spacing == null || spacing.isNegative() ? Duration.ZERO : spacing;
        this.current = this.spacing;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Waits for this request's turn, then claims it. */
    void awaitTurn() throws InterruptedException {
        if (spacing.isZero()) {
            return;
        }
        Duration wait;
        synchronized (this) {
            Instant now = clock.instant();
            Instant start = latest(now, lastStart == null ? null : lastStart.plus(current),
                    lastEnd == null ? null : lastEnd.plus(current));
            lastStart = start;
            wait = Duration.between(now, start);
        }
        if (!wait.isZero()) {
            sleeper.sleep(wait);
        }
    }

    /** Records how a request ended; the next one then waits the (possibly widened) gap from now. */
    synchronized void finished(boolean overloaded) {
        if (spacing.isZero()) {
            return;
        }
        if (overloaded && current.compareTo(MAX_SPACING) < 0) {
            Duration wider = current.multipliedBy(2);
            current = wider.compareTo(MAX_SPACING) > 0 ? MAX_SPACING : wider;
            log.warn("dbGaP looks overloaded; spacing requests {} s apart", current.toMillis() / 1000.0);
        } else if (!overloaded && current.compareTo(spacing) > 0) {
            Duration narrower = current.dividedBy(2);
            current = narrower.compareTo(spacing) < 0 ? spacing : narrower;
            if (current.equals(spacing)) {
                log.info("dbGaP is answering again; spacing requests {} s apart", current.toMillis() / 1000.0);
            }
        }
        lastEnd = latest(clock.instant(), lastEnd);
    }

    private static Instant latest(Instant first, Instant... others) {
        Instant max = first;
        for (Instant i : others) {
            if (i != null && i.isAfter(max)) {
                max = i;
            }
        }
        return max;
    }

    /** The gap now in force. */
    synchronized Duration currentSpacing() {
        return current;
    }
}
