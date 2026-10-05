package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.security.AuthContext;
import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.service.ScopeDefinitionService;
import com.github.guoyyit.consentgrantsjava.service.ScopeEvolutionService;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-only cross-subject consent visibility. Unlike the user endpoint (which is limited
 * to the caller's own consent and only for one client), admins may enumerate all of a
 * subject's grants. Every such read is recorded as an ADMIN action in the audit trail.
 */
@RestController
@RequestMapping("/admin/consents")
public class AdminConsentController {

    private final ConsentRepository consentRepository;
    private final ScopeDefinitionService scopeDefinitionService;
    private final ScopeEvolutionService evolutionService;
    private final AuditService auditService;

    public AdminConsentController(ConsentRepository consentRepository,
                                  ScopeDefinitionService scopeDefinitionService,
                                  ScopeEvolutionService evolutionService,
                                  AuditService auditService) {
        this.consentRepository = consentRepository;
        this.scopeDefinitionService = scopeDefinitionService;
        this.evolutionService = evolutionService;
        this.auditService = auditService;
    }

    @GetMapping("/users/{userId}")
    public List<ConsentView> forUser(@PathVariable String userId,
                                     @RequestParam(defaultValue = "100") int limit) {
        List<ConsentGrantEntity> grants = consentRepository.findGrantsForUser(userId).stream()
                .limit(Math.min(Math.max(limit, 1), 1000))
                .toList();
        auditService.record("admin", userId, null, "ADMIN_CONSENT_READ",
                AuditResult.ALLOWED, "grants=" + grants.size());
        return grants.stream().map(g -> ConsentView.from(toView(g))).toList();
    }

    @GetMapping("/users/{userId}/clients/{clientId}")
    public ConsentView forGrant(@PathVariable String userId, @PathVariable String clientId) {
        List<ConsentGrantEntity> grants = consentRepository.findGrantsForUser(userId).stream()
                .filter(g -> g.clientId().equals(clientId))
                .toList();
        if (grants.isEmpty()) {
            auditService.record("admin", userId, clientId, "ADMIN_CONSENT_READ",
                    AuditResult.DENIED, "no such grant");
            throw new com.github.guoyyit.consentgrantsjava.exception.NotFoundException(
                    "no consent for user=" + userId + " client=" + clientId);
        }
        auditService.record("admin", userId, clientId, "ADMIN_CONSENT_READ",
                AuditResult.ALLOWED, null);
        return ConsentView.from(toView(grants.get(0)));
    }

    private EffectiveConsent toView(ConsentGrantEntity grant) {
        ScopeVersionDefinition definition =
                scopeDefinitionService.getDefinition(grant.scopeVersion());
        Map<String, Decision> decisions =
                consentRepository.findDecisions(grant.userId(), grant.clientId());
        return evolutionService.resolve(grant.userId(), grant.clientId(), definition,
                grant.scopeVersion(), decisions, grant.state(), grant.versionSeq());
    }
}
