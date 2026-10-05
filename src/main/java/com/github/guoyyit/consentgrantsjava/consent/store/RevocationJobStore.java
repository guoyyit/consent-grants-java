package com.github.guoyyit.consentgrantsjava.consent.store;

import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJob;
import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJobStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable queue of revocation propagation jobs. Unfinished jobs survive restarts
 * (in this in-memory implementation they survive in the map; the recovery pass
 * on startup re-enqueues anything not DONE) so propagation always resumes from
 * a recoverable position.
 */
@Repository
public class RevocationJobStore {

    private final Map<String, RevocationJob> jobs = new ConcurrentHashMap<>();

    public RevocationJob save(RevocationJob job) {
        jobs.put(job.jobId(), job);
        return job;
    }

    public Optional<RevocationJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<RevocationJob> findUnfinished() {
        return jobs.values().stream()
                .filter(j -> j.status() != RevocationJobStatus.DONE)
                .toList();
    }
}
