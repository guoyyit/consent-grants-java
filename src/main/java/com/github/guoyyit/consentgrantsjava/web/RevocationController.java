package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.exception.ForbiddenException;
import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.service.RevocationService;
import com.github.guoyyit.consentgrantsjava.web.dto.PropagationView;
import com.github.guoyyit.consentgrantsjava.web.dto.RevokeRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/revocations")
public class RevocationController {

    private final RevocationService revocationService;
    private final AuditService auditService;

    public RevocationController(RevocationService revocationService, AuditService auditService) {
        this.revocationService = revocationService;
        this.auditService = auditService;
    }

    @PostMapping
    public PropagationView revoke(@RequestHeader("X-User-Id") String userId,
                                  @RequestBody RevokeRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            request = new RevokeRequest(userId, request == null ? null : request.clientId());
        } else if (!request.userId().equals(userId)) {
            auditService.record(userId, request.userId(), request.clientId(),
                    "CONSENT_REVOKE_FORBIDDEN", AuditResult.DENIED,
                    "caller attempted to revoke another subject's consent");
            throw new ForbiddenException("cannot revoke consent on behalf of another user");
        }
        RevocationEventEntity event = revocationService.revoke(request.userId(), request.clientId(), userId);
        return PropagationView.from(event);
    }
}
