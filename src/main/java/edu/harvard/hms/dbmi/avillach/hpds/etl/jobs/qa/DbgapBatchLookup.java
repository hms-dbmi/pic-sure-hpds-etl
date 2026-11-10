package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import edu.harvard.hms.dbmi.avillach.hpds.etl.core.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Looks a batch of study versions up in dbGaP, shared by the QA jobs. dbGaP's SSTR API is slow and
 * volatile, so the batch is built to end with a report rather than hammer it or run into the runner's
 * monitor timeout:
 * <ul>
 *   <li>{@code concurrency} lookups at once (the jobs default to 1), each paced by the source's
 *       {@link RequestPacer};</li>
 *   <li>no lookup starts once {@code giveUpAfter} studies in a row got no usable answer (any answer, a
 *       NOT_FOUND included, resets the count), or once {@code deadline} has passed since the first; the
 *       studies not asked are FAILED with 0 attempts;</li>
 *   <li>a study that failed because dbGaP could not be connected to holds back every lookup not yet
 *       started for {@code unreachablePause}, to give dbGaP (or the route to it) room to recover;
 *       lookups already in flight carry on.</li>
 * </ul>
 */
final class DbgapBatchLookup {

    private static final Logger log = LoggerFactory.getLogger(DbgapBatchLookup.class);

    /** A study version to look up, e.g. ("phs001412", "v4"). */
    record Request(String studyId, String version) {
    }

    private DbgapBatchLookup() {
    }

    /**
     * @param source   closed once every lookup is done
     * @param onAnswer called on the lookup's thread with each lookup dbGaP was actually asked for, as it
     *                 finishes (e.g. to cache a summary at once, so an interrupted run keeps it); an
     *                 exception it throws is logged, never fails the lookup
     * @return each request's lookup, by study id, in request order
     */
    static Map<String, DbgapLookup> lookUp(List<Request> toAsk, DbgapStudySource source, String baseUrl,
                                           int concurrency, int giveUpAfter, Duration unreachablePause,
                                           Duration deadline, BiConsumer<Request, DbgapLookup> onAnswer) {
        Map<String, DbgapLookup> lookups = new LinkedHashMap<>();
        if (toAsk.isEmpty()) {
            source.close();
            return lookups;
        }
        log.info("Looking up {} study version(s) in dbGaP at {} ({} at a time)", toAsk.size(), baseUrl,
                Math.min(concurrency, toAsk.size()));
        AtomicInteger threads = new AtomicInteger();
        AtomicInteger failedInARow = new AtomicInteger();
        long stopAt = System.nanoTime() + deadline.toNanos();
        AtomicLong pausedUntil = new AtomicLong(System.nanoTime());
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, toAsk.size()), r -> {
            Thread t = new Thread(r, "dbgap-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try (source) {
            Map<String, Future<DbgapLookup>> futures = new LinkedHashMap<>();
            for (Request in : toAsk) {
                futures.put(in.studyId(), pool.submit(() -> {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    try {
                        long pause = pausedUntil.get() - System.nanoTime();
                        if (pause > 0) {
                            log.info("dbGaP {}.{}: waiting {} s after dbGaP could not be connected to", in.studyId(),
                                    in.version(), TimeUnit.NANOSECONDS.toSeconds(pause) + 1);
                            try {
                                while ((pause = pausedUntil.get() - System.nanoTime()) > 0) {
                                    TimeUnit.NANOSECONDS.sleep(pause);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return DbgapLookup.failed("not asked: interrupted while waiting for dbGaP to recover", 0);
                            }
                        }
                        if (failedInARow.get() >= giveUpAfter) {
                            return DbgapLookup.failed("not asked: dbGaP gave no usable answer for the last "
                                    + failedInARow.get() + " studies in a row, so the run stopped asking", 0);
                        }
                        if (System.nanoTime() - stopAt > 0) {
                            return DbgapLookup.failed("not asked: the " + deadline.toSeconds()
                                    + " s lookup deadline (--dbgap-deadline-seconds) had passed", 0);
                        }
                        DbgapLookup lookup;
                        try {
                            lookup = source.fetch(in.studyId(), in.version());
                        } catch (RuntimeException e) {
                            failedInARow.incrementAndGet();
                            throw e;
                        }
                        if (lookup != null && lookup.outcome() != DbgapLookup.Outcome.FAILED) {
                            failedInARow.set(0);
                        } else {
                            failedInARow.incrementAndGet();
                        }
                        if (lookup != null && lookup.unreachable() && !unreachablePause.isZero()) {
                            long until = System.nanoTime() + unreachablePause.toNanos();
                            pausedUntil.accumulateAndGet(until, Math::max);
                            log.warn("dbGaP could not be connected to for {}.{}; no new lookup starts for {} s",
                                    in.studyId(), in.version(), unreachablePause.toSeconds());
                        }
                        if (lookup != null && onAnswer != null) {
                            try {
                                onAnswer.accept(in, lookup);
                            } catch (RuntimeException e) {
                                log.warn("dbGaP {}.{}: handling the answer failed: {}", in.studyId(), in.version(),
                                        e.toString());
                            }
                        }
                        return lookup;
                    } finally {
                        MDC.clear();
                    }
                }));
            }
            for (Map.Entry<String, Future<DbgapLookup>> e : futures.entrySet()) {
                DbgapLookup lookup;
                try {
                    lookup = e.getValue().get();
                } catch (ExecutionException ex) {
                    // A source is not meant to throw; if one does, that study is unverified, not the run lost.
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    log.error("dbGaP lookup of {} threw", e.getKey(), cause);
                    lookup = DbgapLookup.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage(), 1);
                }
                lookups.put(e.getKey(), lookup == null ? DbgapLookup.failed("no answer", 0) : lookup);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("Interrupted while looking studies up in dbGaP", e);
        } finally {
            pool.shutdownNow();
        }
        return lookups;
    }
}
