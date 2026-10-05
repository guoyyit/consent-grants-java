package com.github.guoyyit.consentgrantsjava.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives convergence of unfinished revocation propagation. Because all progress
 * is durable in the database, a crash between batches is recovered on the next run.
 *
 * Ticking is gated by consent.revocation.propagation-worker-enabled (tests disable it to
 * drive convergence manually and assert pre/post states deterministically).
 */
@Component
public class PropagationScheduler {

    private final RevocationPropagationService propagationService;
    private final boolean enabled;

    public PropagationScheduler(RevocationPropagationService propagationService,
                                @Value("${consent.revocation.propagation-worker-enabled:true}") boolean enabled) {
        this.propagationService = propagationService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${consent.revocation.propagation-interval-ms:200}")
    public void tick() {
        if (enabled) {
            propagationService.processPendingBatch();
        }
    }
}
