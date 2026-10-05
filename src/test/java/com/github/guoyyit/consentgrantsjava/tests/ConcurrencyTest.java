package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import com.github.guoyyit.consentgrantsjava.consent.domain.GrantStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent consent, revocation, and issuance on the same (user, client)
 * must converge to a unique, self-consistent outcome: no grant issued after
 * revocation stays active, and every issued grant has a readable consent.
 */
class ConcurrencyTest {

    private ConsentStore consentStore;
    private GrantStore grantStore;
    private ConsentService consents;
    private GrantService grants;

    @BeforeEach
    void setUp() {
        ConsentCache cache = new ConsentCache();
        ScopeVersionStore scopeStore = new ScopeVersionStore();
        ScopeRegistry scopes = new ScopeRegistry(scopeStore, cache);
        scopes.publish(1, Map.of("a", PermissionItem.active("a")));
        AuditService audit = new AuditService(new AuditLogStore());
        consentStore = new ConsentStore();
        grantStore = new GrantStore();
        RevocationPropagationService revocation =
                new RevocationPropagationService(grantStore, new RevocationJobStore(), audit);
        consents = new ConsentService(consentStore, scopes, cache, audit, revocation);
        grants = new GrantService(consentStore, grantStore, scopes, cache, audit);
    }

    @RepeatedTest(10)
    void concurrentSubmissionsConvergeToSingleRecord() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                consents.submit("u1", "c1", 1, Set.of("a"), Set.of());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // exactly one record, exactly one mutation (first), rest are replays
        ConsentRecord record = consentStore.find("u1", "c1").orElseThrow();
        assertEquals(1, record.revision());
        assertEquals(Set.of("a"), record.grantedItems());
    }

    @RepeatedTest(10)
    void issueAndRevokeRaceYieldsSelfConsistentOutcome() throws Exception {
        consents.submit("u1", "c1", 1, Set.of("a"), Set.of());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<GrantService.IssueResult> issued = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        // half the threads issue, one revokes midway
        for (int i = 0; i < threads - 1; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                issued.add(grants.issue("u1", "c1", Set.of("a")));
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            await(start);
            consents.revoke("u1", "c1");
            return null;
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        ConsentRecord consent = consentStore.find("u1", "c1").orElseThrow();
        assertEquals(ConsentStatus.REVOKED, consent.status());

        // every grant issued before the revoke decision has been converged to
        // REVOKED by propagation; none remains ACTIVE. (If the revoke won the
        // race before any issuance, there are simply no grants — also valid.)
        List<Grant> all = grantStore.findByUserAndClient("u1", "c1");
        for (Grant g : all) {
            assertEquals(GrantStatus.REVOKED, g.status(),
                    "grant " + g.grantId() + " escaped revocation");
        }
        // and every issued grant validates as invalid now
        for (GrantService.IssueResult r : issued) {
            if (r instanceof GrantService.IssueResult.Issued ok) {
                assertTrue(grants.validate(ok.grant().token())
                        instanceof GrantService.ValidationResult.Invalid);
            }
        }
        // issuing after revocation always fails
        assertTrue(grants.issue("u1", "c1", Set.of("a"))
                instanceof GrantService.IssueResult.Denied);
    }

    @Test
    void issuedGrantAlwaysHasReadableConsentRecord() {
        consents.submit("u1", "c1", 1, Set.of("a"), Set.of());
        var result = grants.issue("u1", "c1", Set.of("a"));
        assertTrue(result instanceof GrantService.IssueResult.Issued);
        // the consent record behind the grant is always readable
        ConsentRecord consent = consentStore.find("u1", "c1").orElseThrow();
        assertTrue(consent.isActive());
        Grant grant = ((GrantService.IssueResult.Issued) result).grant();
        assertEquals(consent.revision(), grant.consentRevision());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
