package com.github.guoyyit.consentgrantsjava.consent.service;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.AccessPrincipal;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.store.ConsentStore;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Access-controlled read path for consent state.
 *
 * <p>Visibility rules: a USER principal sees only its own records; an ADMIN
 * sees any single record but the aggregate view exposes counts only, never
 * per-user item details. Every read — allowed, denied, or not found — is
 * audited, so unauthorized access attempts are traceable.
 */
@Service
public class ConsentQueryService {

    public sealed interface ReadResult {
        record Allowed(ConsentRecord record) implements ReadResult {
        }

        record NotFound() implements ReadResult {
        }

        record Forbidden(String reason) implements ReadResult {
        }
    }

    private final ConsentStore store;
    private final ConsentCache cache;
    private final AuditService audit;

    public ConsentQueryService(ConsentStore store, ConsentCache cache, AuditService audit) {
        this.store = store;
        this.cache = cache;
        this.audit = audit;
    }

    /** Reads the consent for (userId, clientId) on behalf of the principal. */
    public ReadResult read(AccessPrincipal principal, String userId, String clientId) {
        if (!principal.isAdmin() && !principal.subject().equals(userId)) {
            audit.record(principal.role(), principal.subject(), "CONSENT_READ",
                    userId, clientId, "DENIED", "cross-user read rejected");
            return new ReadResult.Forbidden("users may only read their own consent");
        }
        Optional<ConsentRecord> record = cache.get(userId, clientId,
                () -> store.find(userId, clientId));
        if (record.isEmpty()) {
            audit.record(principal.role(), principal.subject(), "CONSENT_READ",
                    userId, clientId, "NOT_FOUND", null);
            return new ReadResult.NotFound();
        }
        audit.record(principal.role(), principal.subject(), "CONSENT_READ",
                userId, clientId, "ALLOWED", "revision=" + record.get().revision());
        return new ReadResult.Allowed(record.get());
    }

    /**
     * Admin aggregate view: consent counts per status for a client, without
     * exposing any per-user consent detail.
     */
    public Map<ConsentStatus, Long> adminSummary(AccessPrincipal principal, String clientId) {
        if (!principal.isAdmin()) {
            audit.record(principal.role(), principal.subject(), "CONSENT_SUMMARY",
                    "-", clientId, "DENIED", "admin role required");
            throw new SecurityException("admin role required");
        }
        Map<ConsentStatus, Long> summary = new EnumMap<>(ConsentStatus.class);
        for (ConsentStatus status : ConsentStatus.values()) {
            summary.put(status, 0L);
        }
        for (ConsentRecord record : store.findByClient(clientId)) {
            summary.merge(record.status(), 1L, Long::sum);
        }
        audit.record(principal.role(), principal.subject(), "CONSENT_SUMMARY",
                "-", clientId, "ALLOWED", summary.toString());
        return summary;
    }
}
