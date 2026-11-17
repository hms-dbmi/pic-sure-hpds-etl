package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

/**
 * Where the consent count QA gets dbGaP's view of a study version. {@link DbgapStudyClient} is the
 * real one; tests substitute their own. Must be safe to call from several threads at once.
 */
@FunctionalInterface
public interface DbgapStudySource extends AutoCloseable {

    /**
     * @param studyId e.g. "phs001412"
     * @param version e.g. "v4"
     * @return never null; an outage is {@link DbgapLookup.Outcome#FAILED}, not an exception
     */
    DbgapLookup fetch(String studyId, String version);

    /** Releases any connections; called once the run's lookups are done. */
    @Override
    default void close() {
    }
}
