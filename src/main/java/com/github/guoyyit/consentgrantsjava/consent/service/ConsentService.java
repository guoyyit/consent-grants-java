package com.github.guoyyit.consentgrantsjava.consent.service;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItemStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.ScopeVersion;
import com.github.guoyyit.consentgrantsjava.consent.store.ConsentStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Consent lifecycle: fine-grained submit (idempotent, convergent) and revoke.
 *
 * <p>Convergence rule: one record per (userId, clientId). A submission that
 * equals the current state is a replay — no mutation, no revision bump, no
 * extra audit side effect. A differing submission replaces the state and bumps
 * the revision. All transitions happen under the per-pair lock in
 * {@link ConsentStore}, serialized with grant issuance and revocation.
 */
@Service
public class ConsentService {

    public record SubmitResult(ConsentRecord record, boolean changed, boolean replay) {
    }

    private final ConsentStore store;
    private final ScopeRegistry scopes;
    private final ConsentCache cache;
    private final AuditService audit;
    private final RevocationPropagationService revocation;

    public ConsentService(ConsentStore store, ScopeRegistry scopes, ConsentCache cache,
                          AuditService audit, RevocationPropagationService revocation) {
        this.store = store;
        this.scopes = scopes;
        this.cache = cache;
        this.audit = audit;
        this.revocation = revocation;
    }

    /**
     * Submits per-item consent decisions for (userId, clientId) bound to an
     * exact scope version. Grant and deny sets must be disjoint and every item
     * must be ACTIVE in the bound version.
     */
    public SubmitResult submit(String userId, String clientId, long scopeVersion,
                               Set<String> grant, Set<String> deny) {
        ScopeVersion scope = scopes.require(scopeVersion);
        Set<String> grantSet = grant == null ? Set.of() : new TreeSet<>(grant);
        Set<String> denySet = deny == null ? Set.of() : new TreeSet<>(deny);
        validate(scope, grantSet, denySet);

        ConsentRecord[] result = new ConsentRecord[1];
        boolean[] changed = new boolean[1];
        ConsentRecord record = store.mutate(userId, clientId, current -> {
            if (current != null && current.isActive()
                    && current.scopeVersion() == scopeVersion
                    && current.grantedItems().equals(grantSet)
                    && current.deniedItems().equals(denySet)) {
                // identical re-submission: converge, no side effects
                changed[0] = false;
                return current;
            }
            changed[0] = true;
            long nextRevision = current == null ? 1L : current.revision() + 1;
            return new ConsentRecord(userId, clientId, scopeVersion,
                    grantSet, denySet, ConsentStatus.ACTIVE, nextRevision, Instant.now());
        });
        result[0] = record;

        if (changed[0]) {
            // writer protocol: store already mutated, now bump cache epoch
            cache.invalidate(userId, clientId);
            audit.record(AuditEvent.ActorRole.USER, userId, "CONSENT_SUBMIT",
                    userId, clientId, "CHANGED",
                    "scopeVersion=" + scopeVersion
                            + " granted=" + grantSet + " denied=" + denySet
                            + " revision=" + record.revision());
        }
        return new SubmitResult(record, changed[0], !changed[0]);
    }

    /**
     * Revokes the consent. Idempotent: revoking an already-revoked consent is a
     * no-op. On the ACTIVE→REVOKED transition a durable propagation job is
     * enqueued so every issued grant converges to revoked downstream.
     */
    public ConsentRecord revoke(String userId, String clientId) {
        boolean[] transitioned = new boolean[1];
        ConsentRecord record = store.mutate(userId, clientId, current -> {
            if (current == null) {
                throw new IllegalStateException("no consent to revoke for "
                        + userId + "/" + clientId);
            }
            if (current.status() == ConsentStatus.REVOKED) {
                transitioned[0] = false;
                return current;
            }
            transitioned[0] = true;
            return new ConsentRecord(userId, clientId, current.scopeVersion(),
                    current.grantedItems(), current.deniedItems(),
                    ConsentStatus.REVOKED, current.revision() + 1, Instant.now());
        });

        if (transitioned[0]) {
            cache.invalidate(userId, clientId);
            audit.record(AuditEvent.ActorRole.USER, userId, "CONSENT_REVOKE",
                    userId, clientId, "CHANGED", "revision=" + record.revision());
            revocation.enqueue(userId, clientId);
        }
        return record;
    }

    private void validate(ScopeVersion scope, Set<String> grant, Set<String> deny) {
        Set<String> overlap = new HashSet<>(grant);
        overlap.retainAll(deny);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("items cannot be both granted and denied: "
                    + new TreeSet<>(overlap));
        }
        Set<String> rejected = new HashSet<>();
        for (String code : union(grant, deny)) {
            var item = scope.item(code);
            if (item.isEmpty() || item.get().status() != PermissionItemStatus.ACTIVE) {
                rejected.add(code);
            }
        }
        if (!rejected.isEmpty()) {
            throw new InvalidScopeItemException(scope.version(), rejected);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
