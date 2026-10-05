package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentDecisionEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentSubmissionConvergenceTest extends AbstractIntegrationTest {

    @Autowired ConsentService consentService;
    @Autowired ConsentRepository consentRepository;
    @Autowired AuditService auditService;

    private ConsentSubmitRequest v1(String user, String idem, Decision contacts, Decision calendar) {
        return new ConsentSubmitRequest(user, "client-a", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", contacts.name()),
                new ItemDecisionRequest("calendar.read", calendar.name())), idem);
    }

    @Test
    void repeatedSubmissionConvergesToSingleStateWithoutExtraSideEffects() {
        var first = consentService.submit(v1("user-1", "idem-1", Decision.GRANTED, Decision.DENIED));
        int auditAfterFirst = auditService.recent(1000).size();

        // Exact replay (same key, same payload) must be a no-op.
        var replay1 = consentService.submit(v1("user-1", "idem-1", Decision.GRANTED, Decision.DENIED));
        var replay2 = consentService.submit(v1("user-1", "idem-1", Decision.GRANTED, Decision.DENIED));

        assertThat(replay1.versionSeq()).isEqualTo(first.versionSeq()).isEqualTo(1L);
        assertThat(replay2.versionSeq()).isEqualTo(1L);
        assertThat(auditService.recent(1000)).hasSize(auditAfterFirst);

        // Exactly one grant row and exactly one decision row per item (no duplicates).
        ConsentGrantEntity grant = consentRepository.findGrant("user-1", "client-a");
        assertThat(grant).isNotNull();
        List<ConsentDecisionEntity> decisions =
                consentRepository.findDecisionEntities("user-1", "client-a");
        assertThat(decisions).hasSize(4);
        assertThat(decisions.stream().map(ConsentDecisionEntity::itemKey))
                .doesNotHaveDuplicates();
        assertThat(consentRepository.findDecisions("user-1", "client-a"))
                .containsEntry("contacts.read", Decision.GRANTED);
    }

    @Test
    void changingScopeConvergesToUniqueFinalStateAndBumpsVersionOnce() {
        consentService.submit(v1("user-2", "idem-a", Decision.GRANTED, Decision.DENIED));
        consentService.submit(v1("user-2", "idem-a", Decision.GRANTED, Decision.DENIED)); // no-op replay

        // Reduce scope: deny contacts.
        EffectiveConsent reduced = consentService.submit(v1("user-2", "idem-b", Decision.DENIED, Decision.DENIED));
        assertThat(reduced.versionSeq()).isEqualTo(1L); // content change without version move keeps seq
        assertThat(reduced.grantedKeys()).containsExactlyInAnyOrder("profile.read", "email.read");

        // Re-add it.
        EffectiveConsent widened = consentService.submit(v1("user-2", "idem-c", Decision.GRANTED, Decision.GRANTED));
        assertThat(widened.grantedKeys())
                .containsExactlyInAnyOrder("profile.read", "email.read", "contacts.read", "calendar.read");

        // Still a single grant and one decision row per item.
        assertThat(consentRepository.findGrantsForUser("user-2")).hasSize(1);
        assertThat(consentRepository.findDecisionEntities("user-2", "client-a")).hasSize(4);
        Map<String, Decision> finalDecisions = consentRepository.findDecisions("user-2", "client-a");
        assertThat(finalDecisions.get("contacts.read")).isEqualTo(Decision.GRANTED);
        assertThat(finalDecisions.get("calendar.read")).isEqualTo(Decision.GRANTED);
    }

    @Test
    void partialSubmissionIsRejectedSoStateCannotBeAmbiguous() {
        var partial = new ConsentSubmitRequest("user-3", "client-a", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED")), "idem-x");
        try {
            consentService.submit(partial);
            throw new AssertionError("expected validation failure");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("missing decision");
        }
        assertThat(consentRepository.findGrant("user-3", "client-a")).isNull();
    }
}
