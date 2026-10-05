package com.github.guoyyit.consentgrantsjava.consent.service;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import com.github.guoyyit.consentgrantsjava.consent.domain.GrantStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJob;
import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJobStatus;
import com.github.guoyyit.consentgrantsjava.consent.store.GrantStore;
import com.github.guoyyit.consentgrantsjava.consent.store.RevocationJobStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Propagates consent revocation to every issued grant through durable jobs.
 *
 * <p>Each job carries the set of grant ids still pending. Progress is persisted
 * after every single grant, so a crash or restart resumes from the last
 * completed grant — the system never stays partially revoked: unfinished jobs
 * are re-driven by {@link #recoverUnfinished()} on startup.
 */
@Service
public class RevocationPropagationService {

    private final GrantStore grants;
    private final RevocationJobStore jobs;
    private final AuditService audit;

    /** Test hook: throws to simulate a downstream failure for a grant id. */
    private volatile Consumer<String> failureInjector = grantId -> {
    };

    public RevocationPropagationService(GrantStore grants, RevocationJobStore jobs,
                                        AuditService audit) {
        this.grants = grants;
        this.jobs = jobs;
        this.audit = audit;
    }

    public void setFailureInjector(Consumer<String> failureInjector) {
        this.failureInjector = failureInjector == null ? id -> {
        } : failureInjector;
    }

    /** Durably enqueues a propagation job for all active grants of the pair. */
    public RevocationJob enqueue(String userId, String clientId) {
        Set<String> pending = new TreeSet<>();
        for (Grant g : grants.findByUserAndClient(userId, clientId)) {
            if (g.status() == GrantStatus.ACTIVE) {
                pending.add(g.grantId());
            }
        }
        RevocationJob job = new RevocationJob(
                UUID.randomUUID().toString(), userId, clientId,
                pending, Set.of(),
                pending.isEmpty() ? RevocationJobStatus.DONE : RevocationJobStatus.PENDING,
                0, Instant.now(), Instant.now());
        jobs.save(job);
        audit.record(AuditEvent.ActorRole.SYSTEM, "revocation", "REVOKE_JOB_ENQUEUE",
                userId, clientId, job.status().name(),
                "jobId=" + job.jobId() + " pending=" + pending.size());
        return job.status() == RevocationJobStatus.DONE ? job : process(job.jobId());
    }

    /**
     * Drives one job to completion, persisting progress per grant. A downstream
     * failure leaves the job FAILED with the remaining grants still pending, so
     * a later retry or {@link #recoverUnfinished()} resumes exactly there.
     */
    public synchronized RevocationJob process(String jobId) {
        RevocationJob job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("unknown job: " + jobId));
        if (job.status() == RevocationJobStatus.DONE) {
            return job;
        }
        job = save(job, RevocationJobStatus.IN_PROGRESS, job.pendingGrantIds(),
                job.completedGrantIds(), job.attempts() + 1);

        Set<String> pending = new LinkedHashSet<>(job.pendingGrantIds());
        Set<String> completed = new LinkedHashSet<>(job.completedGrantIds());
        for (String grantId : new ArrayList<>(pending)) {
            try {
                failureInjector.accept(grantId);
                revokeGrant(grantId);
            } catch (RuntimeException e) {
                RevocationJob failed = save(job, RevocationJobStatus.FAILED,
                        pending, completed, job.attempts());
                audit.record(AuditEvent.ActorRole.SYSTEM, "revocation", "REVOKE_JOB_FAILED",
                        job.userId(), job.clientId(), "FAILED",
                        "jobId=" + jobId + " grantId=" + grantId + " error=" + e.getMessage());
                return failed;
            }
            pending.remove(grantId);
            completed.add(grantId);
            job = save(job, RevocationJobStatus.IN_PROGRESS, pending, completed,
                    job.attempts());
        }
        RevocationJob done = save(job, RevocationJobStatus.DONE, pending, completed,
                job.attempts());
        audit.record(AuditEvent.ActorRole.SYSTEM, "revocation", "REVOKE_JOB_DONE",
                done.userId(), done.clientId(), "DONE", "jobId=" + jobId);
        return done;
    }

    /** Re-drives every unfinished job (called on startup after a restart). */
    public List<RevocationJob> recoverUnfinished() {
        List<RevocationJob> recovered = new ArrayList<>();
        for (RevocationJob job : jobs.findUnfinished()) {
            recovered.add(process(job.jobId()));
        }
        return recovered;
    }

    private void revokeGrant(String grantId) {
        Grant grant = grants.findById(grantId)
                .orElseThrow(() -> new IllegalStateException("grant missing: " + grantId));
        if (grant.status() == GrantStatus.REVOKED) {
            return; // already converged: safe to re-drive
        }
        grants.save(new Grant(grant.grantId(), grant.userId(), grant.clientId(),
                grant.scopeVersion(), grant.items(), grant.token(),
                GrantStatus.REVOKED, grant.consentRevision(),
                grant.issuedAt(), Instant.now()));
        audit.record(AuditEvent.ActorRole.SYSTEM, "revocation", "GRANT_REVOKE",
                grant.userId(), grant.clientId(), "REVOKED", "grantId=" + grantId);
    }

    private RevocationJob save(RevocationJob base, RevocationJobStatus status,
                               Set<String> pending, Set<String> completed, int attempts) {
        RevocationJob next = new RevocationJob(base.jobId(), base.userId(), base.clientId(),
                new TreeSet<>(pending), new TreeSet<>(completed), status, attempts,
                base.createdAt(), Instant.now());
        return jobs.save(next);
    }
}
