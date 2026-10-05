package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;
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
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.TokenView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates a crash/restart: the first application instance durably records a revocation
 * whose downstream propagation never runs; a second instance over the same file database
 * resumes and converges from the persisted position.
 */
class RestartRecoveryTest {

    private String propsFile(Path dir, String name) {
        return "spring.datasource.url=jdbc:h2:file:" + dir.resolve(name)
                + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL";
    }

    private ConfigurableApplicationContext start(Path dir, String dbName) {
        // Command-line arguments have the highest property precedence, so they reliably
        // disable the background scheduler for this test even though the packaged
        // application.properties enables it.
        return new SpringApplicationBuilder(com.github.guoyyit.consentgrantsjava.Application.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--" + propsFile(dir, dbName),
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--consent.revocation.propagation-worker-enabled=false",
                        "--consent.cache.enabled=true",
                        "--spring.sql.init.mode=always");
    }

    @Test
    void pendingPropagationSurvivesRestartAndConvergesInSecondInstance(@TempDir Path dir) {
        String tokenValue;
        long revocationId;
        try (ConfigurableApplicationContext first = start(dir, "restart-db")) {
            ConsentService consent = first.getBean(ConsentService.class);
            TokenService tokens = first.getBean(TokenService.class);
            RevocationService revocation = first.getBean(RevocationService.class);
            RevocationRepository revRepo = first.getBean(RevocationRepository.class);

            consent.submit(new ConsentSubmitRequest("restart-user", "client-x", "v1", List.of(
                    new ItemDecisionRequest("profile.read", "GRANTED"),
                    new ItemDecisionRequest("email.read", "GRANTED"),
                    new ItemDecisionRequest("contacts.read", "GRANTED"),
                    new ItemDecisionRequest("calendar.read", "DENIED")), "restart-1"));
            TokenView token = tokens.issue(
                    new IssueTokenRequest("restart-user", "client-x", "v1", null, 600_000),
                    "restart-user");
            tokenValue = token.token();

            // Simulate crash immediately after revocation: never run the propagation worker.
            RevocationEventEntity event =
                    revocation.revoke("restart-user", "client-x", "restart-user");
            revocationId = event.id();
            assertThat(revRepo.findById(revocationId).status()).isEqualTo(RevocationStatus.PENDING);
        } // first context (and its in-memory downstream) is gone -> simulates restart

        try (ConfigurableApplicationContext second = start(dir, "restart-db")) {
            RevocationPropagationService propagation =
                    second.getBean(RevocationPropagationService.class);
            InMemoryDownstreamPropagator downstream =
                    second.getBean(InMemoryDownstreamPropagator.class);
            TokenService tokens = second.getBean(TokenService.class);
            AccessTokenRepository tokenRepo = second.getBean(AccessTokenRepository.class);
            RevocationRepository revRepo = second.getBean(RevocationRepository.class);

            // The durable pending work is still present after restart.
            assertThat(revRepo.findById(revocationId).status()).isEqualTo(RevocationStatus.PENDING);
            AccessTokenEntity stored = tokenRepo.findByValue(tokenValue);
            assertThat(stored.state()).isEqualTo(TokenState.REVOKED);
            assertThat(stored.propagation()).isEqualTo(PropagationState.PENDING);

            int processed = propagation.processPendingBatch();
            assertThat(processed).isEqualTo(1);

            assertThat(revRepo.findById(revocationId).status()).isEqualTo(RevocationStatus.PROPAGATED);
            List<AccessTokenEntity> after = tokenRepo.lockByRevocationId(revocationId);
            assertThat(after).allSatisfy(t ->
                    assertThat(t.propagation()).isEqualTo(PropagationState.DONE));
            assertThat(downstream.isRevoked(tokenValue)).isTrue();
            assertThat(tokens.introspect(new IntrospectRequest(tokenValue, null)).active()).isFalse();
        }
    }
}
