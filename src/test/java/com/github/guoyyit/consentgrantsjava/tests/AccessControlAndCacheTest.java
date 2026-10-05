package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.AccessPrincipal;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentQueryService;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.consent.service.RevocationPropagationService;
import com.github.guoyyit.consentgrantsjava.consent.service.ScopeRegistry;
import com.github.guoyyit.consentgrantsjava.consent.store.AuditLogStore;
import com.github.guoyyit.consentgrantsjava.consent.store.ConsentStore;
import com.github.guoyyit.consentgrantsjava.consent.store.GrantStore;
import com.github.guoyyit.consentgrantsjava.consent.store.RevocationJobStore;
import com.github.guoyyit.consentgrantsjava.consent.store.ScopeVersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-path access control and cache consistency: cross-user reads are denied
 * and audited; cached views never serve stale conclusions after revocation or
 * scope-version changes.
 */
class AccessControlAndCacheTest {

    private ConsentStore consentStore;
    private AuditLogStore auditLogStore;
    private ConsentCache cache;
    private ConsentService consents;
    private ConsentQueryService queries;
    private ScopeRegistry scopes;

    @BeforeEach
    void setUp() {
        consentStore = new ConsentStore();
        auditLogStore = new AuditLogStore();
        AuditService audit = new AuditService(auditLogStore);
        cache = new ConsentCache();
        ScopeVersionStore scopeStore = new ScopeVersionStore();
        scopes = new ScopeRegistry(scopeStore, cache);
        scopes.publish(1, Map.of("a", PermissionItem.active("a"),
                "b", PermissionItem.active("b")));
        RevocationPropagationService revocation =
                new RevocationPropagationService(new GrantStore(), new RevocationJobStore(), audit);
        consents = new ConsentService(consentStore, scopes, cache, audit, revocation);
        queries = new ConsentQueryService(consentStore, cache, audit);
    }

    @Test
    void crossUserReadIsDeniedAndAudited() {
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());

        var result = queries.read(AccessPrincipal.user("mallory"), "alice", "c1");
        assertInstanceOf(ConsentQueryService.ReadResult.Forbidden.class, result);

        // the denied attempt is traceable in the audit trail
        boolean deniedEventLogged = auditLogStore.all().stream().anyMatch(e ->
                e.action().equals("CONSENT_READ")
                        && e.outcome().equals("DENIED")
                        && e.actor().equals("mallory")
                        && e.targetUserId().equals("alice"));
        assertTrue(deniedEventLogged);
    }

    @Test
    void userReadsOwnRecordAndAdminReadsAny() {
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());

        var own = queries.read(AccessPrincipal.user("alice"), "alice", "c1");
        assertInstanceOf(ConsentQueryService.ReadResult.Allowed.class, own);

        var admin = queries.read(AccessPrincipal.admin("ops"), "alice", "c1");
        assertInstanceOf(ConsentQueryService.ReadResult.Allowed.class, admin);
    }

    @Test
    void adminSummaryIsAggregateOnlyAndUserSummaryRejected() {
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());
        consents.submit("bob", "c1", 1, Set.of("b"), Set.of());
        consents.revoke("bob", "c1");

        Map<ConsentStatus, Long> summary =
                queries.adminSummary(AccessPrincipal.admin("ops"), "c1");
        assertEquals(1L, summary.get(ConsentStatus.ACTIVE));
        assertEquals(1L, summary.get(ConsentStatus.REVOKED));

        assertThrows(SecurityException.class,
                () -> queries.adminSummary(AccessPrincipal.user("alice"), "c1"));
    }

    @Test
    void cacheServesUntilRevocationThenReflectsNewState() {
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());

        // warm the cache through the query path
        var first = queries.read(AccessPrincipal.user("alice"), "alice", "c1");
        assertInstanceOf(ConsentQueryService.ReadResult.Allowed.class, first);

        consents.revoke("alice", "c1");

        // the cached view must not serve the pre-revocation state
        var after = queries.read(AccessPrincipal.user("alice"), "alice", "c1");
        ConsentRecord record =
                ((ConsentQueryService.ReadResult.Allowed) after).record();
        assertEquals(ConsentStatus.REVOKED, record.status());
    }

    @Test
    void cacheInvalidatedWhenScopeVersionPublished() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<Optional<ConsentRecord>> loader = () -> {
            loads.incrementAndGet();
            return consentStore.find("alice", "c1");
        };
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());

        cache.get("alice", "c1", loader);
        cache.get("alice", "c1", loader);
        assertEquals(1, loads.get(), "second read should hit the cache");

        scopes.publish(2, Map.of("a", PermissionItem.active("a")));
        cache.get("alice", "c1", loader);
        assertEquals(2, loads.get(), "scope publish must invalidate cached views");
    }

    @Test
    void readMissIsNotFoundAndAudited() {
        var result = queries.read(AccessPrincipal.user("ghost"), "ghost", "c1");
        assertInstanceOf(ConsentQueryService.ReadResult.NotFound.class, result);
        boolean logged = auditLogStore.all().stream().anyMatch(e ->
                e.action().equals("CONSENT_READ") && e.outcome().equals("NOT_FOUND"));
        assertTrue(logged);
    }

    @Test
    void auditEventsAreEmittedForWrites() {
        consents.submit("alice", "c1", 1, Set.of("a"), Set.of());
        consents.revoke("alice", "c1");
        assertTrue(auditLogStore.all().stream().anyMatch(e ->
                e.action().equals("CONSENT_SUBMIT") && e.actorRole() == AuditEvent.ActorRole.USER));
        assertTrue(auditLogStore.all().stream().anyMatch(e ->
                e.action().equals("CONSENT_REVOKE")));
    }
}
