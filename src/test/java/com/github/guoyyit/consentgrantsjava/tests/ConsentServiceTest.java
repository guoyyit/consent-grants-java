package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.consent.service.InvalidScopeItemException;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeated submissions must converge to one explainable state without
 * duplicate records or extra side effects.
 */
class ConsentServiceTest {

    private ConsentStore consentStore;
    private AuditLogStore auditLogStore;
    private ConsentService consents;

    @BeforeEach
    void setUp() {
        consentStore = new ConsentStore();
        auditLogStore = new AuditLogStore();
        AuditService audit = new AuditService(auditLogStore);
        ConsentCache cache = new ConsentCache();
        ScopeVersionStore scopeStore = new ScopeVersionStore();
        ScopeRegistry scopes = new ScopeRegistry(scopeStore, cache);
        scopes.publish(1, Map.of(
                "a", PermissionItem.active("a"),
                "b", PermissionItem.active("b"),
                "c", PermissionItem.active("c")));
        RevocationPropagationService revocation =
                new RevocationPropagationService(new GrantStore(), new RevocationJobStore(), audit);
        consents = new ConsentService(consentStore, scopes, cache, audit, revocation);
    }

    @Test
    void identicalResubmissionIsReplayWithoutSideEffects() {
        var first = consents.submit("u1", "c1", 1, Set.of("a", "b"), Set.of("c"));
        assertTrue(first.changed());
        assertFalse(first.replay());
        long auditEventsAfterFirst = auditLogStore.all().size();

        var second = consents.submit("u1", "c1", 1, Set.of("a", "b"), Set.of("c"));
        assertFalse(second.changed());
        assertTrue(second.replay());

        // same single record, same revision, no extra audit events
        assertEquals(first.record().revision(), second.record().revision());
        assertEquals(auditEventsAfterFirst, auditLogStore.all().size());
        ConsentRecord stored = consentStore.find("u1", "c1").orElseThrow();
        assertEquals(Set.of("a", "b"), stored.grantedItems());
        assertEquals(Set.of("c"), stored.deniedItems());
    }

    @Test
    void scopeCanGrowAndShrinkConvergingToSingleState() {
        consents.submit("u1", "c1", 1, Set.of("a"), Set.of());
        var grown = consents.submit("u1", "c1", 1, Set.of("a", "b", "c"), Set.of());
        assertTrue(grown.changed());
        assertEquals(Set.of("a", "b", "c"), grown.record().grantedItems());

        var shrunk = consents.submit("u1", "c1", 1, Set.of("b"), Set.of("a", "c"));
        assertTrue(shrunk.changed());
        ConsentRecord stored = consentStore.find("u1", "c1").orElseThrow();
        assertEquals(Set.of("b"), stored.grantedItems());
        assertEquals(Set.of("a", "c"), stored.deniedItems());
        assertEquals(3, stored.revision());
    }

    @Test
    void grantAndDenyMustBeDisjoint() {
        assertThrows(IllegalArgumentException.class,
                () -> consents.submit("u1", "c1", 1, Set.of("a"), Set.of("a")));
    }

    @Test
    void unknownItemsAreRejectedWithDistinguishableError() {
        InvalidScopeItemException e = assertThrows(InvalidScopeItemException.class,
                () -> consents.submit("u1", "c1", 1, Set.of("a", "zzz"), Set.of()));
        assertEquals(Set.of("zzz"), e.rejectedItems());
        assertEquals(1, e.scopeVersion());
    }

    @Test
    void revokeIsIdempotentAndAuditedOnce() {
        consents.submit("u1", "c1", 1, Set.of("a"), Set.of());
        ConsentRecord revoked = consents.revoke("u1", "c1");
        assertEquals(ConsentStatus.REVOKED, revoked.status());
        long events = auditLogStore.all().size();

        ConsentRecord again = consents.revoke("u1", "c1");
        assertEquals(ConsentStatus.REVOKED, again.status());
        assertEquals(revoked.revision(), again.revision());
        assertEquals(events, auditLogStore.all().size());
    }

    @Test
    void revokeWithoutConsentFails() {
        assertThrows(IllegalStateException.class, () -> consents.revoke("nobody", "c1"));
    }
}
