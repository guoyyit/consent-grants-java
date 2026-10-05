package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import com.github.guoyyit.consentgrantsjava.consent.domain.GrantStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJob;
import com.github.guoyyit.consentgrantsjava.consent.domain.RevocationJobStatus;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.consent.service.GrantService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revocation propagation: issued credentials fail downstream validation after
 * revocation; a mid-propagation failure leaves a resumable job, and recovery
 * converges the remaining grants instead of staying partially revoked.
 */
class RevocationPropagationTest {

    private ConsentService consents;
    private GrantService grants;
    private RevocationPropagationService revocation;
    private GrantStore grantStore;
    private RevocationJobStore jobStore;

    @BeforeEach
    void setUp() {
        ConsentCache cache = new ConsentCache();
        ScopeVersionStore scopeStore = new ScopeVersionStore();
        ScopeRegistry scopes = new ScopeRegistry(scopeStore, cache);
        scopes.publish(1, Map.of(
                "a", PermissionItem.active("a"),
                "b", PermissionItem.active("b")));
        AuditService audit = new AuditService(new AuditLogStore());
        ConsentStore consentStore = new ConsentStore();
        grantStore = new GrantStore();
        jobStore = new RevocationJobStore();
        revocation = new RevocationPropagationService(grantStore, jobStore, audit);
        consents = new ConsentService(consentStore, scopes, cache, audit, revocation);
        grants = new GrantService(consentStore, grantStore, scopes, cache, audit);
    }

    private Grant issueOne(String user, String item) {
        var result = grants.issue(user, "c1", Set.of(item));
        return ((GrantService.IssueResult.Issued) result).grant();
    }

    @Test
    void revokedConsentFailsDownstreamValidationImmediately() {
        consents.submit("u1", "c1", 1, Set.of("a", "b"), Set.of());
        Grant grant = issueOne("u1", "a");
        assertInstanceOf(GrantService.ValidationResult.Valid.class,
                grants.validate(grant.token()));

        consents.revoke("u1", "c1");

        assertInstanceOf(GrantService.ValidationResult.Invalid.class,
                grants.validate(grant.token()));
        // and propagation has marked the grant itself revoked
        assertEquals(GrantStatus.REVOKED,
                grantStore.findById(grant.grantId()).orElseThrow().status());
    }

    @Test
    void midPropagationFailureLeavesResumableJobAndRecoveryConverges() {
        consents.submit("u1", "c1", 1, Set.of("a", "b"), Set.of());
        Grant g1 = issueOne("u1", "a");
        Grant g2 = issueOne("u1", "b");

        // simulate a downstream failure on the second grant during propagation
        AtomicInteger calls = new AtomicInteger();
        revocation.setFailureInjector(grantId -> {
            if (calls.incrementAndGet() == 2) {
                throw new RuntimeException("downstream unavailable");
            }
        });
        consents.revoke("u1", "c1");

        // the job is FAILED with exactly one grant still pending: no partial
        // state is hidden — the remaining work is durable and inspectable
        RevocationJob unfinished = jobStore.findUnfinished().stream().findFirst().orElseThrow();
        assertEquals(RevocationJobStatus.FAILED, unfinished.status());
        assertEquals(1, unfinished.pendingGrantIds().size());
        assertEquals(1, unfinished.completedGrantIds().size());

        // downstream validation already fails for both grants via consent state
        assertInstanceOf(GrantService.ValidationResult.Invalid.class,
                grants.validate(g1.token()));
        assertInstanceOf(GrantService.ValidationResult.Invalid.class,
                grants.validate(g2.token()));

        // simulate restart: failure cleared, recovery resumes from the pending grant
        revocation.setFailureInjector(null);
        var recovered = revocation.recoverUnfinished();
        assertEquals(1, recovered.size());
        assertEquals(RevocationJobStatus.DONE, recovered.get(0).status());
        assertTrue(jobStore.findUnfinished().isEmpty());
        assertEquals(GrantStatus.REVOKED,
                grantStore.findById(g1.grantId()).orElseThrow().status());
        assertEquals(GrantStatus.REVOKED,
                grantStore.findById(g2.grantId()).orElseThrow().status());
    }

    @Test
    void recoveryIsIdempotentWhenNothingIsPending() {
        consents.submit("u1", "c1", 1, Set.of("a"), Set.of());
        issueOne("u1", "a");
        consents.revoke("u1", "c1");
        assertTrue(jobStore.findUnfinished().isEmpty());
        assertTrue(revocation.recoverUnfinished().isEmpty());
    }
}
