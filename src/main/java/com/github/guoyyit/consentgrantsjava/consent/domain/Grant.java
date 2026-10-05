package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * A constrained access credential issued after user consent.
 *
 * @param consentRevision the consent revision this grant was issued against;
 *                        downstream validation compares it with the current
 *                        consent revision to detect stale grants.
 */
public record Grant(
        String grantId,
        String userId,
        String clientId,
        long scopeVersion,
        Set<String> items,
        String token,
        GrantStatus status,
        long consentRevision,
        Instant issuedAt,
        Instant revokedAt) {

    public Grant {
        items = items == null ? Set.of() : new TreeSet<>(items);
    }

    public boolean isActive() {
        return status == GrantStatus.ACTIVE;
    }
}
