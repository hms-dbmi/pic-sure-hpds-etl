package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when told to: a fake sleep advances it, so waits are exact and nothing sleeps. */
final class ManualClock extends Clock {

    private Instant now = Instant.parse("2026-10-23T12:00:00Z");

    synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }
}
