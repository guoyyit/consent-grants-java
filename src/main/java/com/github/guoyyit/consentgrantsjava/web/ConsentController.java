package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.exception.ForbiddenException;
import com.github.guoyyit.consentgrantsjava.security.AuthContext;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.service.ConsentService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentView;
import org.springframework.web.bind.annotation.*;

/**
 * User-facing consent lifecycle. Caller identity is supplied via X-User-Id; a caller may
 * only act on its own consent. Cross-subject access lives under /admin and requires the
 * admin key.
 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consentService;
    private final AuditService auditService;

    public ConsentController(ConsentService consentService, AuditService auditService) {
        this.consentService = consentService;
        this.auditService = auditService;
    }

    @PostMapping
    public ConsentView submit(@RequestHeader("X-User-Id") String userId,
                              @RequestBody ConsentSubmitRequest request) {
        if (request.userId() != null && !request.userId().isBlank()
                && !request.userId().equals(userId)) {
            auditService.record(userId, request.userId(), request.clientId(),
                    "CONSENT_SUBMIT_FORBIDDEN", AuditResult.DENIED,
                    "caller attempted to submit consent for another subject");
            throw new ForbiddenException("cannot submit consent on behalf of another user");
        }
        ConsentSubmitRequest bound = new ConsentSubmitRequest(userId, request.clientId(),
                request.scopeVersion(), request.decisions(), request.idempotencyKey());
        EffectiveConsent effective = consentService.submit(bound);
        return ConsentView.from(effective);
    }

    @GetMapping("/{clientId}")
    public ConsentView get(@RequestHeader("X-User-Id") String userId,
                           @RequestHeader(value = "X-On-Behalf-User", required = false) String onBehalf,
                           @PathVariable String clientId) {
        // A user may only read its own consent detail. Explicitly naming another subject
        // is rejected and recorded; it never leaks the target's items.
        if (onBehalf != null && !onBehalf.isBlank() && !onBehalf.equals(userId)) {
            auditService.record(userId, onBehalf, clientId, "CONSENT_READ_FORBIDDEN",
                    AuditResult.DENIED, "user attempted to read another subject's consent");
            throw new ForbiddenException("not authorized to read another user's consent");
        }
        EffectiveConsent effective =
                consentService.getEffective(userId, clientId, AuthContext.user(userId));
        auditService.record(userId, userId, clientId, "CONSENT_READ", AuditResult.ALLOWED, null);
        return ConsentView.from(effective);
    }
}
