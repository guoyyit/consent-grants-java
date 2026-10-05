package com.github.guoyyit.consentgrantsjava.consent.api;

import com.github.guoyyit.consentgrantsjava.consent.audit.AuditService;
import com.github.guoyyit.consentgrantsjava.consent.domain.AccessPrincipal;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentStatus;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.domain.ScopeVersion;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentQueryService;
import com.github.guoyyit.consentgrantsjava.consent.service.ScopeRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints: scope version publishing, aggregate summaries, audit trail.
 * All require the ADMIN role; aggregates never expose per-user item detail.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record PublishScopeRequest(long version, Map<String, PermissionItem> items) {
    }

    private final ScopeRegistry scopes;
    private final ConsentQueryService queries;
    private final AuditService audit;
    private final PrincipalFactory principals;

    public AdminController(ScopeRegistry scopes, ConsentQueryService queries,
                           AuditService audit, PrincipalFactory principals) {
        this.scopes = scopes;
        this.queries = queries;
        this.audit = audit;
        this.principals = principals;
    }

    @PostMapping("/scopes")
    public ResponseEntity<ScopeVersion> publish(@RequestBody PublishScopeRequest request) {
        return ResponseEntity.ok(scopes.publish(request.version(), request.items()));
    }

    @GetMapping("/consents/summary/{clientId}")
    public ResponseEntity<Map<ConsentStatus, Long>> summary(
            @PathVariable String clientId,
            @RequestHeader(PrincipalFactory.SUBJECT_HEADER) String subject,
            @RequestHeader(value = PrincipalFactory.ROLE_HEADER, required = false) String role) {
        AccessPrincipal principal = principals.from(subject, role);
        return ResponseEntity.ok(queries.adminSummary(principal, clientId));
    }

    @GetMapping("/audit/{userId}/{clientId}")
    public ResponseEntity<List<AuditEvent>> auditTrail(
            @PathVariable String userId,
            @PathVariable String clientId,
            @RequestHeader(PrincipalFactory.SUBJECT_HEADER) String subject,
            @RequestHeader(value = PrincipalFactory.ROLE_HEADER, required = false) String role) {
        AccessPrincipal principal = principals.from(subject, role);
        if (!principal.isAdmin()) {
            throw new SecurityException("admin role required");
        }
        return ResponseEntity.ok(audit.trail(userId, clientId));
    }
}
