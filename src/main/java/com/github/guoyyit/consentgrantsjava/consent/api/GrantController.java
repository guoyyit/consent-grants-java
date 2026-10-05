package com.github.guoyyit.consentgrantsjava.consent.api;

import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import com.github.guoyyit.consentgrantsjava.consent.service.GrantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Credential issuance and downstream validation endpoints.
 */
@RestController
@RequestMapping("/api/grants")
public class GrantController {

    public record IssueRequest(Set<String> items) {
    }

    public record ValidateRequest(String token) {
    }

    public record GrantView(String grantId, String userId, String clientId,
                            long scopeVersion, Set<String> items, String token,
                            String status) {
        static GrantView of(Grant g) {
            return new GrantView(g.grantId(), g.userId(), g.clientId(), g.scopeVersion(),
                    g.items(), g.token(), g.status().name());
        }
    }

    private final GrantService grants;

    public GrantController(GrantService grants) {
        this.grants = grants;
    }

    @PostMapping("/{userId}/{clientId}")
    public ResponseEntity<?> issue(@PathVariable String userId,
                                   @PathVariable String clientId,
                                   @RequestBody IssueRequest request) {
        GrantService.IssueResult result = grants.issue(userId, clientId, request.items());
        if (result instanceof GrantService.IssueResult.Issued issued) {
            return ResponseEntity.ok(GrantView.of(issued.grant()));
        }
        return ResponseEntity.status(409).body(Map.of(
                "error", "ISSUE_DENIED",
                "message", ((GrantService.IssueResult.Denied) result).reason()));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ValidateRequest request) {
        GrantService.ValidationResult result = grants.validate(request.token());
        if (result instanceof GrantService.ValidationResult.Valid valid) {
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "grantId", valid.grant().grantId()));
        }
        return ResponseEntity.ok(Map.of(
                "valid", false,
                "reason", ((GrantService.ValidationResult.Invalid) result).reason()));
    }
}
