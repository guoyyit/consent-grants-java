package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.time.Instant;

/**
 * Append-only audit record. Every state transition and every read of consent
 * details (allowed or denied) is traced here.
 */
public record AuditEvent(
        String eventId,
        Instant at,
        String actor,
        ActorRole actorRole,
        String action,
        String targetUserId,
        String targetClientId,
        String outcome,
        String detail) {

    public enum ActorRole {
        USER,
        ADMIN,
        SYSTEM
    }
}
