package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.exception.ForbiddenException;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.service.TokenService;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectView;
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.TokenView;
import org.springframework.web.bind.annotation.*;

@RestController
public class TokenController {

    private final TokenService tokenService;
    private final AuditService auditService;

    public TokenController(TokenService tokenService, AuditService auditService) {
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    @PostMapping("/api/tokens")
    public TokenView issue(@RequestHeader("X-User-Id") String userId,
                           @RequestBody IssueTokenRequest request) {
        if (request.userId() != null && !request.userId().isBlank()
                && !request.userId().equals(userId)) {
            auditService.record(userId, request.userId(), request.clientId(),
                    "TOKEN_ISSUE_FORBIDDEN", AuditResult.DENIED,
                    "caller attempted to issue a token for another subject");
            throw new ForbiddenException("cannot issue a token on behalf of another user");
        }
        IssueTokenRequest bound = new IssueTokenRequest(userId, request.clientId(),
                request.scopeVersion(), request.requestedScopes(), request.ttlMillis());
        return tokenService.issue(bound, userId);
    }

    @PostMapping("/api/tokens/introspect")
    public IntrospectView introspect(@RequestBody IntrospectRequest request) {
        return tokenService.introspect(request);
    }
}
