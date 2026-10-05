package com.github.guoyyit.consentgrantsjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end over HTTP: consent → issue → validate → revoke → validate fails;
 * stale-version submissions get a distinguishable 422; cross-user reads 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ConsentApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // consent to two items of the bootstrapped v1 scope
        mvc.perform(post("/api/consents/u1/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeVersion\":1,"
                                + "\"grant\":[\"profile.read\",\"email.read\"],"
                                + "\"deny\":[\"profile.write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.consent.revision").value(1));

        // identical resubmission is a replay
        mvc.perform(post("/api/consents/u1/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeVersion\":1,"
                                + "\"grant\":[\"profile.read\",\"email.read\"],"
                                + "\"deny\":[\"profile.write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.consent.revision").value(1));

        // issue a credential for a consented item
        MvcResult issued = mvc.perform(post("/api/grants/u1/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[\"profile.read\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String token = om.readTree(issued.getResponse().getContentAsString())
                .get("token").asText();

        // downstream validation passes
        mvc.perform(post("/api/grants/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // issuing for a denied item fails
        mvc.perform(post("/api/grants/u1/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[\"profile.write\"]}"))
                .andExpect(status().isConflict());

        // revoke, then validation must fail
        mvc.perform(delete("/api/consents/u1/app1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(post("/api/grants/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void staleVersionSubmissionGetsDistinguishable422() throws Exception {
        // publish v2 where profile.read is split
        mvc.perform(post("/api/admin/scopes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"items\":{"
                                + "\"profile.read\":{\"code\":\"profile.read\","
                                + "  \"status\":\"SPLIT\",\"replacedBy\":[\"profile.read.basic\"]},"
                                + "\"profile.read.basic\":{\"code\":\"profile.read.basic\","
                                + "  \"status\":\"ACTIVE\",\"replacedBy\":[]},"
                                + "\"profile.write\":{\"code\":\"profile.write\","
                                + "  \"status\":\"ACTIVE\",\"replacedBy\":[]},"
                                + "\"email.read\":{\"code\":\"email.read\","
                                + "  \"status\":\"ACTIVE\",\"replacedBy\":[]}"
                                + "}}"))
                .andExpect(status().isOk());

        // submitting the split item against v2 is rejected with 422 + details
        mvc.perform(post("/api/consents/u2/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeVersion\":2,"
                                + "\"grant\":[\"profile.read\"],\"deny\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SCOPE_ITEM"))
                .andExpect(jsonPath("$.rejectedItems[0]").value("profile.read"));

        // the same item is still consentable when bound to v1 explicitly
        mvc.perform(post("/api/consents/u2/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeVersion\":1,"
                                + "\"grant\":[\"profile.read\"],\"deny\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void crossUserReadIsForbidden() throws Exception {
        mvc.perform(post("/api/consents/alice/app1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeVersion\":1,"
                                + "\"grant\":[\"profile.read\"],\"deny\":[]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/consents/alice/app1")
                        .header("X-Subject", "mallory"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/consents/alice/app1")
                        .header("X-Subject", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void adminSummaryRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/admin/consents/summary/app1")
                        .header("X-Subject", "alice"))
                .andExpect(status().isForbidden());

        MvcResult ok = mvc.perform(get("/api/admin/consents/summary/app1")
                        .header("X-Subject", "ops")
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode summary = om.readTree(ok.getResponse().getContentAsString());
        assertTrue(summary.has("ACTIVE"));
        assertFalse(summary.has("grantedItems"), "summary must not leak item detail");
    }
}
