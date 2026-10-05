package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * The unique, converged consent state for one (userId, clientId) pair.
 * Repeated submissions converge into this single record; {@code revision}
 * increases monotonically and drives optimistic concurrency and cache validity.
 */
public record ConsentRecord(
        String userId,
        String clientId,
        long scopeVersion,
        Set<String> grantedItems,
        Set<String> deniedItems,
        ConsentStatus status,
        long revision,
        Instant updatedAt) {

    public ConsentRecord {
        grantedItems = grantedItems == null ? Set.of() : new TreeSet<>(grantedItems);
        deniedItems = deniedItems == null ? Set.of() : new TreeSet<>(deniedItems);
    }

    public boolean isActive() {
        return status == ConsentStatus.ACTIVE;
    }
}
