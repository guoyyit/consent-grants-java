package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;
import com.github.guoyyit.consentgrantsjava.exception.ValidationException;
import com.github.guoyyit.consentgrantsjava.exception.VersionConflictException;
import com.github.guoyyit.consentgrantsjava.repo.AccessTokenRepository;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.IntrospectView;
import com.github.guoyyit.consentgrantsjava.web.dto.IssueTokenRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.TokenView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TokenService {

    private static final long DEFAULT_TTL_MILLIS = 3_600_000L;

    private final AccessTokenRepository tokenRepository;
    private final ConsentRepository consentRepository;
    private final ScopeDefinitionService scopeService;
    private final ScopeEvolutionService evolutionService;
    private final InMemoryDownstreamPropagator downstream;
    private final AuditService auditService;

    public TokenService(AccessTokenRepository tokenRepository,
                        ConsentRepository consentRepository,
                        ScopeDefinitionService scopeService,
                        ScopeEvolutionService evolutionService,
                        InMemoryDownstreamPropagator downstream,
                        AuditService auditService) {
        this.tokenRepository = tokenRepository;
        this.consentRepository = consentRepository;
        this.scopeService = scopeService;
        this.evolutionService = evolutionService;
        this.downstream = downstream;
        this.auditService = auditService;
    }

    /**
     * Issues a constrained credential.
     *
     * The grant row is locked, so issuance is strictly ordered against submit/revoke:
     *  - a revocation committed first makes this fail (no token after revocation);
     *  - the committed consent is read in the same transaction, so an issued token always
     *    corresponds to a readable grant (no issue-without-consent).
     *
     * The token is bound to the grant's pinned scope version; a request that explicitly
     * asks for a different version is rejected distinctly rather than silently rebinding.
     */
    @Transactional
    public TokenView issue(IssueTokenRequest request, String actor) {
        validate(request);
        long now = System.currentTimeMillis();

        ConsentGrantEntity grant = consentRepository.lockGrant(request.userId(), request.clientId());
        if (grant == null) {
            throw new ValidationException("no consent grant to issue token against");
        }
        if (grant.state() == GrantState.REVOKED) {
            auditService.record(actor, request.userId(), request.clientId(),
                    "TOKEN_ISSUE_DENIED_REVOKED", AuditResult.DENIED,
                    "issuance attempted after revocation");
            throw new ValidationException("consent has been revoked; cannot issue token");
        }
        if (request.scopeVersion() != null && !request.scopeVersion().isBlank()
                && !request.scopeVersion().equals(grant.scopeVersion())) {
            throw new VersionConflictException("grant is pinned to scope version "
                    + grant.scopeVersion() + " but token request targets "
                    + request.scopeVersion());
        }

        Map<String, Decision> decisions =
                consentRepository.findDecisions(request.userId(), request.clientId());
        EffectiveConsent effective = evolutionService.resolve(
                grant.userId(), grant.clientId(),
                scopeService.getDefinition(grant.scopeVersion()),
                grant.scopeVersion(), decisions, grant.state(), grant.versionSeq());

        LinkedHashSet<String> granted = new LinkedHashSet<>(effective.grantedKeys());
        if (request.requestedScopes() != null && !request.requestedScopes().isEmpty()) {
            LinkedHashSet<String> constrained = new LinkedHashSet<>();
            for (String requested : request.requestedScopes()) {
                if (!granted.contains(requested)) {
                    throw new ValidationException(
                            "requested scope not granted under version " + grant.scopeVersion()
                                    + ": " + requested);
                }
                constrained.add(requested);
            }
            granted = constrained;
        }
        if (granted.isEmpty()) {
            throw new ValidationException("no granted scopes; cannot issue token");
        }

        long ttl = request.ttlMillis() > 0 ? request.ttlMillis() : DEFAULT_TTL_MILLIS;
        AccessTokenEntity token = new AccessTokenEntity(
                UUID.randomUUID().toString().replace("-", ""),
                request.userId(), request.clientId(), grant.scopeVersion(),
                String.join(",", granted), now, now + ttl,
                TokenState.ACTIVE, PropagationState.NOT_REQUIRED, 0, null, null);
        tokenRepository.insert(token);

        auditService.record(actor, request.userId(), request.clientId(),
                "TOKEN_ISSUE", AuditResult.ALLOWED,
                "scopeVersion=" + grant.scopeVersion() + " scopes=" + granted);

        return new TokenView(token.tokenValue(), token.userId(), token.clientId(),
                token.scopeVersion(), List.copyOf(granted), token.issuedAt(),
                token.expiresAt(), token.state().name());
    }

    /**
     * Downstream credential validation. A token is active only when the local durable
     * state says ACTIVE, it has not expired, and the downstream store has not recorded
     * its revocation — so a propagated revocation immediately invalidates verification
     * even if a caller held an earlier snapshot.
     */
    @Transactional(readOnly = true)
    public IntrospectView introspect(IntrospectRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new ValidationException("token is required");
        }
        AccessTokenEntity token = tokenRepository.findByValue(request.token());
        long now = System.currentTimeMillis();

        if (token == null) {
            return inactive();
        }
        boolean downstreamRevoked = downstream.isRevoked(token.tokenValue());
        if (token.state() != TokenState.ACTIVE || downstreamRevoked || now >= token.expiresAt()) {
            return new IntrospectView(false, token.userId(), token.clientId(),
                    token.scopeVersion(), List.of(),
                    downstreamRevoked ? TokenState.REVOKED.name() : token.state().name(),
                    token.propagation().name());
        }
        return new IntrospectView(true, token.userId(), token.clientId(), token.scopeVersion(),
                splitCsv(token.scopeKeysCsv()), token.state().name(), token.propagation().name());
    }

    private void validate(IssueTokenRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()
                || request.clientId() == null || request.clientId().isBlank()) {
            throw new ValidationException("userId and clientId are required");
        }
    }

    private static IntrospectView inactive() {
        return new IntrospectView(false, null, null, null, List.of(), "NONE", "NONE");
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
