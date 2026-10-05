package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;
import com.github.guoyyit.consentgrantsjava.repo.AccessTokenRepository;
import com.github.guoyyit.consentgrantsjava.repo.RevocationRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.service.InMemoryDownstreamPropagator;
import com.github.guoyyit.consentgrantsjava.service.RevocationPropagationService;
import com.github.guoyyit.consentgrantsjava.service.RevocationService;
import com.github.guoyyit.consentgrantsjava.service.TokenService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectView;
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.TokenView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevocationPropagationTest extends AbstractIntegrationTest {

    @Autowired ConsentService consentService;
    @Autowired TokenService tokenService;
    @Autowired RevocationService revocationService;
    @Autowired RevocationPropagationService propagationService;
    @Autowired RevocationRepository revocationRepository;
    @Autowired AccessTokenRepository tokenRepository;
    @Autowired InMemoryDownstreamPropagator downstream;

    private void grantConsent(String user) {
        consentService.submit(new ConsentSubmitRequest(user, "client-r", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", "GRANTED"),
                new ItemDecisionRequest("calendar.read", "DENIED")), "rev-" + user));
    }

    private String issue(String user) {
        TokenView t = tokenService.issue(
                new IssueTokenRequest(user, "client-r", "v1", null, 600_000), user);
        return t.token();
    }

    @Test
    void revokedTokenFailsIntrospectionImmediatelyAndPropagationConvergesAfterInjectedFailure() {
        String user = "rev-user-1";
        grantConsent(user);
        String token = issue(user);

        // The credential verifies while active.
        assertThat(tokenService.introspect(new IntrospectRequest(token, null)).active()).isTrue();

        RevocationEventEntity event = revocationService.revoke(user, "client-r", user);

        // Local state is already revoked: downstream verification must reject immediately,
        // even before propagation finishes.
        IntrospectView rightAfter = tokenService.introspect(new IntrospectRequest(token, null));
        assertThat(rightAfter.active()).isFalse();
        assertThat(rightAfter.state()).isEqualTo("REVOKED");

        // Work is durably recorded but not yet propagated.
        RevocationEventEntity pending0 = revocationRepository.findById(event.id());
        assertThat(pending0.status()).isEqualTo(RevocationStatus.PENDING);
        assertThat(tokenRepository.lockByRevocationId(event.id())).hasSize(1);

        // The first convergence attempt fails mid-flight (simulated downstream outage).
        downstream.setFailNextN(1);
        int processed = propagationService.processPendingBatch();
        assertThat(processed).isEqualTo(1);

        // Work remains recoverable: event PENDING and the token records its failed attempt.
        RevocationEventEntity pending = revocationRepository.findById(event.id());
        assertThat(pending.status()).isEqualTo(RevocationStatus.PENDING);
        List<AccessTokenEntity> tokens = tokenRepository.lockByRevocationId(event.id());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).propagation().name()).isEqualTo("PENDING");
        assertThat(tokens.get(0).attempts()).isGreaterThanOrEqualTo(1);
        assertThat(downstream.isRevoked(token)).isFalse();

        // Resume from the durable position: convergence succeeds and the event completes.
        propagationService.processPendingBatch();
        RevocationEventEntity done = revocationRepository.findById(event.id());
        assertThat(done.status()).isEqualTo(RevocationStatus.PROPAGATED);
        assertThat(downstream.isRevoked(token)).isTrue();

        // Re-driving an already-converged event is a harmless idempotent no-op.
        propagationService.processOne(event.id());
        assertThat(revocationRepository.findById(event.id()).status()).isEqualTo(RevocationStatus.PROPAGATED);
        assertThat(tokenService.introspect(new IntrospectRequest(token, null)).active()).isFalse();
    }

    @Test
    void revocationWithNoActiveTokensCompletesImmediately() {
        String user = "rev-user-2";
        grantConsent(user);
        RevocationEventEntity event = revocationService.revoke(user, "client-r", user);
        assertThat(event.status()).isEqualTo(RevocationStatus.PROPAGATED);
    }

    @Test
    void repeatedRevocationIsSafeAndDoesNotReopenPropagation() {
        String user = "rev-user-3";
        grantConsent(user);
        String token = issue(user);
        RevocationEventEntity first = revocationService.revoke(user, "client-r", user);
        propagationService.processPendingBatch();
        assertThat(revocationRepository.findById(first.id()).status())
                .isEqualTo(RevocationStatus.PROPAGATED);

        RevocationEventEntity again = revocationService.revoke(user, "client-r", user);
        // No new active tokens exist, so the new event is already fully propagated.
        assertThat(again.status()).isEqualTo(RevocationStatus.PROPAGATED);
        assertThat(tokenService.introspect(new IntrospectRequest(token, null)).active()).isFalse();
    }
}
