package com.github.guoyyit.consentgrantsjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AccessControlAuditWebTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditService auditService;

    private String consentJson() throws Exception {
        return objectMapper.writeValueAsString(new ConsentSubmitRequest("owner", "client-ac", "v1",
                List.of(
                        new ItemDecisionRequest("profile.read", "GRANTED"),
                        new ItemDecisionRequest("email.read", "GRANTED"),
                        new ItemDecisionRequest("contacts.read", "DENIED"),
                        new ItemDecisionRequest("calendar.read", "DENIED")),
                "ac-1"));
    }

    @Test
    void adminEndpointsRequireKeyAndUnauthorizedAttemptsAreAudited() throws Exception {
        // Without key -> 401.
        mvc.perform(get("/admin/scope-versions"))
                .andExpect(status().isUnauthorized());

        // With correct key -> 200 and seeded versions visible.
        mvc.perform(get("/admin/scope-versions").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("v1")));

        // The denied attempt is traceable in the admin audit trail.
        boolean foundDenial = auditService.recent(1000).stream()
                .anyMatch(e -> "ADMIN_ACCESS_DENIED".equals(e.action())
                        && e.result().name().equals("DENIED"));
        org.assertj.core.api.Assertions.assertThat(foundDenial).isTrue();
    }

    @Test
    void userCanReadOwnConsentButNotAnotherSubjects() throws Exception {
        mvc.perform(post("/api/consents")
                        .header("X-User-Id", "owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentJson()))
                .andExpect(status().isOk());

        // Owner reads own consent.
        mvc.perform(get("/api/consents/client-ac").header("X-User-Id", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("owner"))
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        // Another user naming the owner via X-On-Behalf-User is forbidden (no detail leaked).
        mvc.perform(get("/api/consents/client-ac")
                        .header("X-User-Id", "intruder")
                        .header("X-On-Behalf-User", "owner"))
                .andExpect(status().isForbidden());

        // Cross-subject denial is audited with the target subject recorded.
        boolean found = auditService.recent(1000).stream()
                .anyMatch(e -> "CONSENT_READ_FORBIDDEN".equals(e.action())
                        && "owner".equals(e.subjectUser())
                        && "intruder".equals(e.actor()));
        org.assertj.core.api.Assertions.assertThat(found).isTrue();
    }

    @Test
    void adminCanReadAcrossSubjectsButUserCannotUseAdminScope() throws Exception {
        mvc.perform(post("/api/consents")
                        .header("X-User-Id", "owner2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsentSubmitRequest(
                                "owner2", "client-ac2", "v1",
                                List.of(
                                        new ItemDecisionRequest("profile.read", "GRANTED"),
                                        new ItemDecisionRequest("email.read", "GRANTED"),
                                        new ItemDecisionRequest("contacts.read", "DENIED"),
                                        new ItemDecisionRequest("calendar.read", "DENIED")),
                                "ac-2"))))
                .andExpect(status().isOk());

        // Admin cross-subject enumeration succeeds.
        mvc.perform(get("/admin/consents/users/owner2")
                        .header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("owner2"));

        // A plain user cannot reach the admin surface even if they know the path.
        mvc.perform(get("/admin/consents/users/owner2"))
                .andExpect(status().isUnauthorized());
    }
}
