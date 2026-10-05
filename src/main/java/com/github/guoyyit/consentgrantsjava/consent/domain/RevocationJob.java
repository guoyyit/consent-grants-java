package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * Durable unit of revocation propagation. Tracks which grants still need to be
 * revoked downstream so a crash or restart can resume from a recoverable position
 * instead of leaving the system partially revoked.
 */
public record RevocationJob(
        String jobId,
        String userId,
        String clientId,
        Set<String> pendingGrantIds,
        Set<String> completedGrantIds,
        RevocationJobStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    public RevocationJob {
        pendingGrantIds = pendingGrantIds == null ? Set.of() : new TreeSet<>(pendingGrantIds);
        completedGrantIds = completedGrantIds == null ? Set.of() : new TreeSet<>(completedGrantIds);
    }
}
