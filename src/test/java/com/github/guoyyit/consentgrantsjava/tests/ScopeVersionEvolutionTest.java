package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveItem;
import com.github.guoyyit.consentgrantsjava.domain.ItemStatus;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.exception.NotFoundException;
import com.github.guoyyit.consentgrantsjava.exception.VersionConflictException;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.service.ScopeDefinitionService;
import com.github.guoyyit.consentgrantsjava.service.TokenService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.TokenView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeVersionEvolutionTest extends AbstractIntegrationTest {

    @Autowired ConsentService consentService;
    @Autowired ScopeDefinitionService scopeService;
    @Autowired TokenService tokenService;

    private ConsentSubmitRequest v1(String user, Decision contacts) {
        return new ConsentSubmitRequest(user, "client-ev", "v1", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", contacts.name()),
                new ItemDecisionRequest("calendar.read", "DENIED")), "v1-" + user);
    }

    private EffectiveItem item(EffectiveConsent c, String key) {
        return c.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void requestIsDeterministicallyBoundToRequestedVersionAndUnknownVersionRejected() {
        // Submitting against an unknown version fails distinctly and creates nothing.
        var bad = new ConsentSubmitRequest("user-ev-x", "client-ev", "v999",
                List.of(new ItemDecisionRequest("profile.read", "GRANTED")), "x");
        assertThatThrownBy(() -> consentService.submit(bad))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("v999");
    }

    @Test
    void splitPredecessorIsNotAmplifiedToSuccessorsAndNewItemIsNotAutoGranted() {
        // Consent given on v1 includes contacts.read GRANTED.
        consentService.submit(v1("user-ev-1", Decision.GRANTED));
        TokenView v1Token = tokenService.issue(
                new IssueTokenRequest("user-ev-1", "client-ev", "v1", null, 600_000), "user-ev-1");
        assertThat(v1Token.scopes()).contains("contacts.read");

        // Issue a token demanding v2 while the grant is still pinned to v1: rejected distinctly.
        assertThatThrownBy(() -> tokenService.issue(
                new IssueTokenRequest("user-ev-1", "client-ev", "v2", null, 600_000), "user-ev-1"))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("v2");

        // Re-pin to v2 with explicit full-state decisions: the user only grants profile/email,
        // leaves split children and offline_access DENIED.
        var v2 = new ConsentSubmitRequest("user-ev-1", "client-ev", "v2", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", "DENIED"),
                new ItemDecisionRequest("contacts.phone.read", "DENIED"),
                new ItemDecisionRequest("contacts.address.read", "DENIED"),
                new ItemDecisionRequest("calendar.read", "DENIED"),
                new ItemDecisionRequest("offline_access", "DENIED")), "v2-user-ev-1");
        EffectiveConsent after = consentService.submit(v2);
        assertThat(after.scopeVersion()).isEqualTo("v2");
        assertThat(after.versionSeq()).isEqualTo(2L);

        // Even though the stored v1 predecessor contacts.read was GRANTED, the split children
        // were explicitly DENIED and must never appear as grants.
        assertThat(after.grantedKeys()).containsExactlyInAnyOrder("profile.read", "email.read");
        TokenView v2Token = tokenService.issue(
                new IssueTokenRequest("user-ev-1", "client-ev", "v2", null, 600_000), "user-ev-1");
        assertThat(v2Token.scopes())
                .containsExactlyInAnyOrder("profile.read", "email.read")
                .doesNotContain("contacts.phone.read", "contacts.address.read", "offline_access");

        // Requesting a non-granted scope is refused.
        assertThatThrownBy(() -> tokenService.issue(
                new IssueTokenRequest("user-ev-1", "client-ev", "v2",
                        List.of("offline_access"), 600_000), "user-ev-1"))
                .hasMessageContaining("not granted");
    }

    @Test
    void renamedEquivalentItemCarriesDecisionOverAndIsExplainable() {
        // Publish a v3 that renames calendar.read -> calendar.events.read via 'replaces'.
        ScopeVersionDefinition v3 = new ScopeVersionDefinition("v3", List.of(
                new ScopeItemDef("profile.read", ItemStatus.ACTIVE, null, List.of(), "profile"),
                new ScopeItemDef("email.read", ItemStatus.ACTIVE, null, List.of(), "email"),
                new ScopeItemDef("calendar.events.read", ItemStatus.ACTIVE, "calendar.read",
                        List.of(), "calendar events"),
                new ScopeItemDef("calendar.read", ItemStatus.RETIRED, null, List.of(), "old calendar")));
        scopeService.publish(v3);

        // User granted calendar.read on v1.
        consentService.submit(v1("user-ev-2", Decision.DENIED));
        // Re-pin full-state to v3: do NOT provide a decision for calendar.events.read;
        // equivalent migration is observable only when a decision is absent. Provide the
        // other two explicit items, and explicitly DENY retired calendar.read.
        var req = new ConsentSubmitRequest("user-ev-2", "client-ev", "v3", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("calendar.events.read", "GRANTED"),
                new ItemDecisionRequest("calendar.read", "DENIED")), "v3-user-ev-2");
        EffectiveConsent c = consentService.submit(req);
        EffectiveItem events = item(c, "calendar.events.read");
        assertThat(events.decision()).isEqualTo(Decision.GRANTED);
        assertThat(events.source()).isEqualTo("EXPLICIT");
        // retired old key must not be granted in the v3 view
        assertThat(c.grantedKeys()).doesNotContain("calendar.read");
    }

    @Test
    void brandNewScopeItemHasNoGrantUntilExplicitlyConsented() {
        // user on v1; build an effective view pinned to v2 by submitting v2 with offline
        // access left DENIED while profile/email granted.
        consentService.submit(v1("user-ev-3", Decision.DENIED));
        var v2 = new ConsentSubmitRequest("user-ev-3", "client-ev", "v2", List.of(
                new ItemDecisionRequest("profile.read", "GRANTED"),
                new ItemDecisionRequest("email.read", "GRANTED"),
                new ItemDecisionRequest("contacts.read", "DENIED"),
                new ItemDecisionRequest("contacts.phone.read", "DENIED"),
                new ItemDecisionRequest("contacts.address.read", "DENIED"),
                new ItemDecisionRequest("calendar.read", "DENIED"),
                new ItemDecisionRequest("offline_access", "DENIED")), "v2-user-ev-3");
        EffectiveConsent c = consentService.submit(v2);
        EffectiveItem offline = item(c, "offline_access");
        assertThat(offline.decision()).isEqualTo(Decision.DENIED);
        assertThat(offline.source()).isEqualTo("EXPLICIT");
        assertThat(c.grantedKeys()).doesNotContain("offline_access");
    }

    @Test
    void republishingAnExistingVersionNeverMutatesHistory() {
        ScopeVersionDefinition altered = new ScopeVersionDefinition("v1", List.of(
                new ScopeItemDef("profile.read", ItemStatus.ACTIVE, null, List.of(), "changed"),
                new ScopeItemDef("email.read", ItemStatus.ACTIVE, null, List.of(), "email"),
                new ScopeItemDef("contacts.read", ItemStatus.ACTIVE, null, List.of(), "c"),
                new ScopeItemDef("calendar.read", ItemStatus.ACTIVE, null, List.of(), "cal"),
                new ScopeItemDef("injected.scope", ItemStatus.ACTIVE, null, List.of(), "injected")));
        scopeService.publish(altered); // must be ignored as v1 already exists
        Map<String, ScopeItemDef> items = scopeService.getDefinition("v1").itemMap();
        assertThat(items).hasSize(4);
        assertThat(items).doesNotContainKey("injected.scope");
    }
}
