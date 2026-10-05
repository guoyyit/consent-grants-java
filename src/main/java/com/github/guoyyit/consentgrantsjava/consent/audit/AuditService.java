package com.github.guoyyit.consentgrantsjava.consent.audit;

import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.store.AuditLogStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes audit events for every state transition and every consent-detail read
 * (allowed or denied), so unauthorized access attempts are traceable.
 */
@Service
public class AuditService {

    private final AuditLogStore store;

    public AuditService(AuditLogStore store) {
        this.store = store;
    }

    public void record(AuditEvent.ActorRole role, String actor, String action,
                       String targetUserId, String targetClientId,
                       String outcome, String detail) {
        store.append(new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                actor,
                role,
                action,
                targetUserId,
                targetClientId,
                outcome,
                detail));
    }

    public List<AuditEvent> trail(String userId, String clientId) {
        return store.forTarget(userId, clientId);
    }
}
