package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.consent.service.GrantService;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scope definition evolution: added, split, and deprecated items. Historical
 * consents are neither silently voided nor amplified; submissions bind to an
 * exact version and stale-version items are rejected distinguishably.
 */
class ScopeVersionEvolutionTest {

    private ScopeRegistry scopes;
    private ConsentService consents;
    private GrantService grants;

    @BeforeEach
    void setUp() {
        ConsentCache cache = new ConsentCache();
        ScopeVersionStore scopeStore = new ScopeVersionStore();
        scopes = new ScopeRegistry(scopeStore, cache);
        AuditService audit = new AuditService(new AuditLogStore());
        ConsentStore consentStore = new ConsentStore();
        GrantStore grantStore = new GrantStore();
        RevocationPropagationService revocation =
                new RevocationPropagationService(grantStore, new RevocationJobStore(), audit);
        consents = new ConsentService(consentStore, scopes, cache, audit, revocation);
        grants = new GrantService(consentStore, grantStore, scopes, cache, audit);

        // v1: coarse "files" plus a soon-to-be-deprecated "legacy"
        scopes.publish(1, Map.of(
                "files", PermissionItem.active("files"),
                "legacy", PermissionItem.active("legacy")));
        // v2: "files" split into read/write, "legacy" deprecated, "share" added
        scopes.publish(2, Map.of(
                "files", PermissionItem.split("files",
                        java.util.List.of("files.read", "files.write")),
                "files.read", PermissionItem.active("files.read"),
                "files.write", PermissionItem.active("files.write"),
                "legacy", PermissionItem.deprecated("legacy"),
                "share", PermissionItem.active("share")));
    }

    @Test
    void versionsMustIncreaseMonotonically() {
        assertThrows(IllegalArgumentException.class,
                () -> scopes.publish(2, Map.of("x", PermissionItem.active("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> scopes.publish(1, Map.of("x", PermissionItem.active("x"))));
    }

    @Test
    void splitItemsExpandAndDeprecatedItemsDrop() {
        Set<String> migrated = scopes.migrate(Set.of("files", "legacy"), 1, 2);
        assertEquals(Set.of("files.read", "files.write"), migrated);
    }

    @Test
    void unknownItemsInTargetVersionDropWithoutAmplification() {
        Set<String> migrated = scopes.migrate(Set.of("nonexistent"), 1, 2);
        assertTrue(migrated.isEmpty());
    }

    @Test
    void submissionBindsToExactVersionAndRejectsStaleItems() {
        // "files" is consentable in v1 but SPLIT (not consentable) in v2
        consents.submit("u1", "c1", 1, Set.of("files"), Set.of());
        InvalidScopeItemException e = assertThrows(InvalidScopeItemException.class,
                () -> consents.submit("u1", "c1", 2, Set.of("files"), Set.of()));
        assertEquals(Set.of("files"), e.rejectedItems());
        // deprecated item cannot be consented in v2 either
        assertThrows(InvalidScopeItemException.class,
                () -> consents.submit("u1", "c1", 2, Set.of("legacy"), Set.of()));
        // new item only exists in v2
        assertThrows(InvalidScopeItemException.class,
                () -> consents.submit("u2", "c1", 1, Set.of("share"), Set.of()));
    }

    @Test
    void historicalConsentStillServesGrantsAfterSplit() {
        // consent given under v1 for coarse "files"
        consents.submit("u1", "c1", 1, Set.of("files"), Set.of());
        // after v2 is published, the historical consent maps to the split items:
        // issuing for a fine-grained item succeeds, proving the old consent was
        // neither silently voided nor needs re-submission
        var issued = grants.issue("u1", "c1", Set.of("files.read"));
        assertInstanceOf(GrantService.IssueResult.Issued.class, issued);
    }

    @Test
    void deprecatedItemConsentCannotBeUsedAfterEvolution() {
        consents.submit("u1", "c1", 1, Set.of("legacy"), Set.of());
        // "legacy" deprecated in v2: the historical consent grants nothing now
        var denied = grants.issue("u1", "c1", Set.of("legacy"));
        assertInstanceOf(GrantService.IssueResult.Denied.class, denied);
    }
}
