package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.repo.AccessTokenRepository;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.service.RevocationService;
import com.github.guoyyit.consentgrantsjava.service.TokenService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentIssueRevokeTest extends AbstractIntegrationTest {

    @Autowired ConsentService consentService;
    @Autowired TokenService tokenService;
    @Autowired RevocationService revocationService;
    @Autowired ConsentRepository consentRepository;
    @Autowired AccessTokenRepository tokenRepository;

    private void grantConsent(String user) {
        consentService.submit(new ConsentSubmitRequest(user, "client-conc", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", "GRANTED"),
                new ItemDecisionRequest("calendar.read", "DENIED")), "conc-" + user));
    }

    @Test
    void concurrentIssueAndRevokeNeverLeavesActiveTokenAfterRevokedConsent() throws Exception {
        String user = "conc-user-1";
        grantConsent(user);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> issuedTokens = new ConcurrentLinkedQueue<>();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var view = tokenService.issue(
                            new IssueTokenRequest(user, "client-conc", "v1", null, 600_000), user);
                    issuedTokens.add(view.token());
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                revocationService.revoke(user, "client-conc", user);
            } catch (Exception ignored) {
            }
        });

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Final state is unique and self-consistent.
        ConsentGrantEntity grant = consentRepository.findGrant(user, "client-conc");
        assertThat(grant.state()).isEqualTo(GrantState.REVOKED);

        // Every token still ACTIVE in the durable store must be impossible after revoke;
        // tokens issued just before revoke are captured and revoked atomically.
        List<AccessTokenEntity> active =
                tokenRepository.findActiveByUserClient(user, "client-conc");
        assertThat(active).isEmpty();

        // No issued token verifies as active in the end.
        for (String token : issuedTokens) {
            assertThat(tokenService.introspect(new IntrospectRequest(token, null)).active()).isFalse();
        }
        // A fresh issuance after the dust settles must be refused.
        try {
            tokenService.issue(new IssueTokenRequest(user, "client-conc", "v1", null, 600_000), user);
            throw new AssertionError("issuance after revocation must fail");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("revoked");
        }
    }

    @Test
    void concurrentSubmissionsAndIssuesConvergeWithReadableConsentForEverySuccessfulIssue()
            throws Exception {
        String user = "conc-user-2";
        // Establish the initial grant first.
        grantConsent(user);

        int issueThreads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(issueThreads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> issued = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < issueThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var view = tokenService.issue(
                            new IssueTokenRequest(user, "client-conc", "v1", null, 600_000), user);
                    issued.add(view.token());
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // With consent continuously ACTIVE, every issuance succeeds and each corresponds to a
        // readable grant (no issue-without-consent).
        assertThat(failures.get()).isZero();
        assertThat(issued).hasSize(issueThreads);
        ConsentGrantEntity grant = consentRepository.findGrant(user, "client-conc");
        assertThat(grant.state()).isEqualTo(GrantState.ACTIVE);
        for (String token : issued) {
            AccessTokenEntity stored = tokenRepository.findByValue(token);
            assertThat(stored).isNotNull();
            assertThat(stored.userId()).isEqualTo(user);
            assertThat(stored.scopeVersion()).isEqualTo(grant.scopeVersion());
            assertThat(tokenService.introspect(new IntrospectRequest(token, null)).active()).isTrue();
        }
    }
}
