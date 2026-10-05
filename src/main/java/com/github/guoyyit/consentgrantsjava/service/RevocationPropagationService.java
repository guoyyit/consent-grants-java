package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;
import com.github.guoyyit.consentgrantsjava.repo.AccessTokenRepository;
import com.github.guoyyit.consentgrantsjava.repo.RevocationRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Converges unfinished revocation propagation.
 *
 * Each revocation event is an independent unit of work processed in its own transaction.
 * Tokens that have already been pushed remain DONE (idempotent downstream), so a failure
 * or crash after a partial batch simply resumes on the next tick / after restart; the
 * system never settles in an ambiguous "partially revoked" state because tokens are
 * REVOKED locally the instant consent is revoked, and DONE is reached for all of them
 * before the event is marked PROPAGATED.
 */
@Service
public class RevocationPropagationService {

    private final RevocationRepository revocationRepository;
    private final AccessTokenRepository tokenRepository;
    private final DownstreamTokenPropagator propagator;
    private final AuditService auditService;
    private final int batchSize;
    private final int maxAttempts;

    public RevocationPropagationService(RevocationRepository revocationRepository,
                                        AccessTokenRepository tokenRepository,
                                        DownstreamTokenPropagator propagator,
                                        AuditService auditService,
                                        @Value("${consent.revocation.propagation-batch-size:100}") int batchSize,
                                        @Value("${consent.revocation.propagation-max-attempts:50}") int maxAttempts) {
        this.revocationRepository = revocationRepository;
        this.tokenRepository = tokenRepository;
        this.propagator = propagator;
        this.auditService = auditService;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Processes one batch of pending revocation events; safe to retry and restart. */
    public int processPendingBatch() {
        List<RevocationEventEntity> pending = revocationRepository.findPending(batchSize);
        int processed = 0;
        for (RevocationEventEntity event : pending) {
            processOne(event.id());
            processed++;
        }
        return processed;
    }

    /** Processes a single revocation event to convergence (or records retryable failure). */
    public void processOne(long revocationId) {
        RevocationEventEntity event = revocationRepository.findById(revocationId);
        if (event == null || event.status() == RevocationStatus.PROPAGATED) {
            return;
        }
        long now = System.currentTimeMillis();
        List<AccessTokenEntity> tokens = tokenRepository.lockByRevocationId(revocationId);

        int failures = 0;
        for (AccessTokenEntity token : tokens) {
            if (token.propagation() == PropagationState.DONE) {
                continue; // idempotent resume: never re-push
            }
            int attempts = token.attempts() + 1;
            try {
                propagator.propagateRevocation(token);
                tokenRepository.updateRevocation(token, TokenState.REVOKED, PropagationState.DONE,
                        revocationId, attempts, null);
            } catch (RuntimeException e) {
                failures++;
                String message = e.getMessage();
                if (attempts >= maxAttempts) {
                    // Exhausted retries: leave a visible, queryable FAILED marker but keep
                    // the event PENDING so operators can inspect and re-drive it.
                    tokenRepository.updateRevocation(token, TokenState.REVOKED, PropagationState.FAILED,
                            revocationId, attempts, truncate(message));
                } else {
                    tokenRepository.updateRevocation(token, TokenState.REVOKED, PropagationState.PENDING,
                            revocationId, attempts, truncate(message));
                }
            }
        }

        int stillPending =
                tokenRepository.countByRevocationIdAndPropagation(revocationId, PropagationState.PENDING);
        int failed =
                tokenRepository.countByRevocationIdAndPropagation(revocationId, PropagationState.FAILED);
        int attempts = event.attempts() + 1;
        if (stillPending == 0 && failed == 0) {
            revocationRepository.updateStatus(revocationId, RevocationStatus.PROPAGATED,
                    attempts, null, now);
            auditService.record("system", event.userId(), event.clientId(),
                    "REVOCATION_PROPAGATED", AuditResult.ALLOWED,
                    "tokens=" + tokens.size());
        } else {
            // Keep PENDING so the scheduler/operator keeps converging after restart.
            revocationRepository.updateStatus(revocationId, RevocationStatus.PENDING, attempts,
                    "pending=" + stillPending + " failed=" + failed, now);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.substring(0, Math.min(s.length(), 1000));
    }
}
