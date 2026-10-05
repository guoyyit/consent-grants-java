package com.github.guoyyit.consentgrantsjava.consent.service;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import com.github.guoyyit.consentgrantsjava.consent.domain.GrantStatus;
import com.github.guoyyit.consentgrantsjava.consent.store.ConsentStore;
import com.github.guoyyit.consentgrantsjava.consent.store.GrantStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Issues constrained credentials from active consents and validates them
 * downstream.
 *
 * <p>Concurrency: issuance runs under the same per-(userId, clientId) lock as
 * consent submit/revoke, and the grant is persisted while the lock is held.
 * Therefore a grant is never issued after a revocation decision, and a
 * successful issuance always implies a readable consent record.
 *
 * <p>Validation re-checks the live consent state, so a revoked consent fails
 * downstream validation immediately — even while revocation propagation to the
 * grant store is still in flight.
 */
@Service
public class GrantService {

    public sealed interface IssueResult {
        record Issued(Grant grant) implements IssueResult {
        }

        /** No active consent covers the requested items. */
        record Denied(String reason) implements IssueResult {
        }
    }

    public sealed interface ValidationResult {
        record Valid(Grant grant) implements ValidationResult {
        }

        record Invalid(String reason) implements ValidationResult {
        }
    }

    private final ConsentStore consents;
    private final GrantStore grants;
    private final ScopeRegistry scopes;
    private final ConsentCache cache;
    private final AuditService audit;

    public GrantService(ConsentStore consents, GrantStore grants, ScopeRegistry scopes,
                        ConsentCache cache, AuditService audit) {
        this.consents = consents;
        this.grants = grants;
        this.scopes = scopes;
        this.cache = cache;
        this.audit = audit;
    }

    /**
     * Issues a credential for the requested items (interpreted in the current
     * scope version) if the active consent — migrated to the current version —
     * covers them.
     */
    public IssueResult issue(String userId, String clientId, Set<String> items) {
        Set<String> requested = items == null ? Set.of() : new TreeSet<>(items);
        if (requested.isEmpty()) {
            return new IssueResult.Denied("no items requested");
        }
        IssueResult result = consents.withLock(userId, clientId, () -> {
            Optional<ConsentRecord> current = consents.find(userId, clientId);
            if (current.isEmpty() || !current.get().isActive()) {
                return new IssueResult.Denied("no active consent");
            }
            ConsentRecord consent = current.get();
            long currentScope = scopes.current().version();
            Set<String> grantedNow = scopes.migrate(consent.grantedItems(),
                    consent.scopeVersion(), currentScope);
            if (!grantedNow.containsAll(requested)) {
                Set<String> missing = new TreeSet<>(requested);
                missing.removeAll(grantedNow);
                return new IssueResult.Denied("consent does not cover: " + missing);
            }
            Grant grant = new Grant(
                    UUID.randomUUID().toString(),
                    userId,
                    clientId,
                    currentScope,
                    requested,
                    UUID.randomUUID().toString(),
                    GrantStatus.ACTIVE,
                    consent.revision(),
                    Instant.now(),
                    null);
            grants.save(grant); // persisted while the lock is held
            return new IssueResult.Issued(grant);
        });
        if (result instanceof IssueResult.Issued issued) {
            audit.record(AuditEvent.ActorRole.SYSTEM, "grant-service", "GRANT_ISSUE",
                    userId, clientId, "ISSUED", "grantId=" + issued.grant().grantId()
                            + " items=" + requested);
        } else {
            audit.record(AuditEvent.ActorRole.SYSTEM, "grant-service", "GRANT_ISSUE",
                    userId, clientId, "DENIED",
                    ((IssueResult.Denied) result).reason());
        }
        return result;
    }

    /**
     * Downstream validation. Fails when the grant was revoked, when the consent
     * is no longer active, or when the consent no longer covers the grant's
     * items after scope-version migration.
     */
    public ValidationResult validate(String token) {
        Optional<Grant> found = grants.findByToken(token);
        if (found.isEmpty()) {
            return new ValidationResult.Invalid("unknown token");
        }
        Grant grant = found.get();
        if (grant.status() != GrantStatus.ACTIVE) {
            return new ValidationResult.Invalid("grant revoked");
        }
        Optional<ConsentRecord> consent = cache.get(grant.userId(), grant.clientId(),
                () -> consents.find(grant.userId(), grant.clientId()));
        if (consent.isEmpty() || !consent.get().isActive()) {
            return new ValidationResult.Invalid("consent revoked");
        }
        ConsentRecord current = consent.get();
        // Each originally granted item must still map to something in the
        // consent's current scope version, and everything it maps to must
        // still be granted. A split followed by partial re-consent therefore
        // converges the credential instead of silently amplifying it.
        for (String item : grant.items()) {
            Set<String> mapped = scopes.migrate(Set.of(item),
                    grant.scopeVersion(), current.scopeVersion());
            if (mapped.isEmpty() || !current.grantedItems().containsAll(mapped)) {
                return new ValidationResult.Invalid("consent no longer covers granted items");
            }
        }
        return new ValidationResult.Valid(grant);
    }

    public Optional<Grant> find(String grantId) {
        return grants.findById(grantId);
    }
}
