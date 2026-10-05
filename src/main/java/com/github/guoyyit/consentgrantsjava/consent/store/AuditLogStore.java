package com.github.guoyyit.consentgrantsjava.consent.store;

import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only audit trail.
 */
@Repository
public class AuditLogStore {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    public void append(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> all() {
        return List.copyOf(events);
    }

    public List<AuditEvent> forTarget(String userId, String clientId) {
        return events.stream()
                .filter(e -> e.targetUserId().equals(userId) && e.targetClientId().equals(clientId))
                .toList();
    }
}
