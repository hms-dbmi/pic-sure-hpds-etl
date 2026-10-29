package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.migration;

import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Consent;
import edu.harvard.hms.dbmi.avillach.hpds.etl.model.Sample;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ConsentRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.ParticipantRepository;
import edu.harvard.hms.dbmi.avillach.hpds.etl.repository.SampleRepository;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mocked repositories backed by in-memory lists, so unit tests can run the migration and its
 * sub-jobs end to end and assert on the rows they wrote without a database. Not transactional:
 * a job that fails after writing leaves its rows here, unlike against Postgres. Every call is
 * serialized on the store, since the migration loads studies from several threads.
 */
final class InMemoryParticipantStore {

    final ParticipantRepository participants = mock(ParticipantRepository.class);
    final ConsentRepository consents = mock(ConsentRepository.class);
    final SampleRepository samples = mock(SampleRepository.class);

    /** "source|sourceId" &rarr; hpds id. */
    final Map<String, Long> participantIds = new LinkedHashMap<>();
    final List<Consent> consentRows = new ArrayList<>();
    final List<Sample> sampleRows = new ArrayList<>();
    final List<String> purgedStudies = new ArrayList<>();

    @SuppressWarnings("unchecked")
    InMemoryParticipantStore() {
        when(participants.resolveOrCreate(any(), anyString(), anyInt())).thenAnswer(locked(inv -> {
            Collection<String> ids = inv.getArgument(0);
            String source = inv.getArgument(1);
            Map<String, Long> resolved = new LinkedHashMap<>();
            int inserted = 0;
            for (String id : ids) {
                String key = source + "|" + id;
                if (!participantIds.containsKey(key)) {
                    participantIds.put(key, (long) participantIds.size() + 1);
                    inserted++;
                }
                resolved.put(id, participantIds.get(key));
            }
            return new ParticipantRepository.Resolution(resolved, inserted);
        }));
        when(participants.findIdsChunked(anyList(), anyString(), anyInt())).thenAnswer(locked(inv -> {
            List<String> ids = inv.getArgument(0);
            String source = inv.getArgument(1);
            Map<String, Long> found = new LinkedHashMap<>();
            for (String id : ids) {
                Long hpdsId = participantIds.get(source + "|" + id);
                if (hpdsId != null) {
                    found.put(id, hpdsId);
                }
            }
            return found;
        }));
        when(consents.deleteByStudyId(anyString())).thenAnswer(locked(inv -> {
            String studyId = inv.getArgument(0);
            purgedStudies.add(studyId);
            int before = consentRows.size();
            consentRows.removeIf(c -> c.studyId().equals(studyId));
            return before - consentRows.size();
        }));
        when(consents.batchUpsert(anyList())).thenAnswer(locked(inv -> {
            List<Consent> rows = inv.getArgument(0);
            consentRows.addAll(rows);
            return rows.size();
        }));
        when(samples.batchUpsert(anyList())).thenAnswer(locked(inv -> {
            List<Sample> rows = inv.getArgument(0);
            sampleRows.addAll(rows);
            return rows.size();
        }));
    }

    private Answer<Object> locked(Answer<?> answer) {
        return inv -> {
            synchronized (this) {
                return answer.answer(inv);
            }
        };
    }

    long hpdsId(String source, String sourceId) {
        Long id = participantIds.get(source + "|" + sourceId);
        if (id == null) {
            throw new AssertionError("no participant " + source + "|" + sourceId + " in " + participantIds.keySet());
        }
        return id;
    }

    List<Consent> consentsFor(String studyId) {
        return consentRows.stream().filter(c -> c.studyId().equals(studyId)).toList();
    }

    List<Sample> samplesFrom(String source) {
        return sampleRows.stream().filter(s -> s.sampleSource().equals(source)).toList();
    }

    /** Lets TransactionTemplate run its callback without a real DataSource/connection. */
    static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }
}
