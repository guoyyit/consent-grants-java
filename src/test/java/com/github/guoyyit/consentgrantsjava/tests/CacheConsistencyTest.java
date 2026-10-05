package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.domain.ItemStatus;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.security.AuthContext;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.service.RevocationService;
import com.github.guoyyit.consentgrantsjava.service.ScopeDefinitionService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConsistencyTest extends AbstractIntegrationTest {

    @Autowired ConsentService consentService;
    @Autowired RevocationService revocationService;
    @Autowired ConsentCache cache;
    @Autowired ScopeDefinitionService scopeService;

    private ConsentSubmitRequest v1(String user, String idem,
                                    String contacts, String calendar) {
        return new ConsentSubmitRequest(user, "client-cache", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", contacts),
                new ItemDecisionRequest("calendar.read", calendar)), idem);
    }

    @Test
    void cachedReadReflectsSubmissionAndIsInvalidatedAfterChangeAndRevoke() {
        String user = "cache-user-1";
        consentService.submit(v1(user, "c1", "GRANTED", "DENIED"));

        EffectiveConsent first =
                consentService.getEffective(user, "client-cache", AuthContext.admin());
        assertThat(first.grantedKeys()).contains("contacts.read");
        assertThat(cache.size()).isGreaterThan(0);

        EffectiveConsent cached =
                consentService.getEffective(user, "client-cache", AuthContext.admin());
        assertThat(cached.grantedKeys()).isEqualTo(first.grantedKeys());

        consentService.submit(v1(user, "c2", "DENIED", "DENIED"));
        EffectiveConsent narrowed =
                consentService.getEffective(user, "client-cache", AuthContext.admin());
        assertThat(narrowed.grantedKeys()).doesNotContain("contacts.read");

        revocationService.revoke(user, "client-cache", user);
        EffectiveConsent revoked =
                consentService.getEffective(user, "client-cache", AuthContext.admin());
        assertThat(revoked.state()).isEqualTo(GrantState.REVOKED);
        assertThat(revoked.grantedKeys()).isEmpty();
    }

    @Test
    void readsStartingAfterInvalidationNeverServeStaleGrant() throws Exception {
        String user = "cache-user-2";
        consentService.submit(v1(user, "c1", "GRANTED", "DENIED"));
        consentService.getEffective(user, "client-cache", AuthContext.admin()); // warm cache

        // Invalidation completes before the reader wave starts: every one of these reads
        // starts strictly after revoke, so none may observe the prior granted conclusion.
        revocationService.revoke(user, "client-cache", user);

        int readers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean staleGrant = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        EffectiveConsent view = consentService.getEffective(
                                user, "client-cache", AuthContext.admin());
                        reads.incrementAndGet();
                        if (view.state() == GrantState.ACTIVE
                                || view.grantedKeys().contains("contacts.read")) {
                            staleGrant.set(true);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(reads.get()).isEqualTo(readers * 200);
        assertThat(staleGrant.get()).isFalse();
    }

    @Test
    void publishingNewScopeVersionFlushesAllCaches() {
        String user = "cache-user-3";
        consentService.submit(v1(user, "c1", "GRANTED", "DENIED"));
        consentService.getEffective(user, "client-cache", AuthContext.admin());
        assertThat(cache.size()).isGreaterThan(0);

        ScopeVersionDefinition fresh = new ScopeVersionDefinition("vCacheX1", List.of(
                new ScopeItemDef("only.scope", ItemStatus.ACTIVE, null, List.of(), "only")));
        scopeService.publish(fresh);
        assertThat(cache.size()).isZero();
        assertThat(scopeService.versions()).contains("vCacheX1");
    }
}
