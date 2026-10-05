package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.exception.ConflictException;
import com.github.guoyyit.consentgrantsjava.exception.NotFoundException;
import com.github.guoyyit.consentgrantsjava.exception.ValidationException;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.IdempotencyRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentDecisionEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.IdempotencyRecordEntity;
import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.security.AuthContext;
import com.github.guoyyit.consentgrantsjava.web.dto.ConsentSubmitRequest;
import com.github.guoyyit.consentgrantsjava.web.dto.ItemDecisionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final ScopeDefinitionService scopeService;
    private final ScopeEvolutionService evolutionService;
    private final ConsentCache consentCache;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditService auditService;

    public ConsentService(ConsentRepository consentRepository,
                          ScopeDefinitionService scopeService,
                          ScopeEvolutionService evolutionService,
                          ConsentCache consentCache,
                          IdempotencyRepository idempotencyRepository,
                          AuditService auditService) {
        this.consentRepository = consentRepository;
        this.scopeService = scopeService;
        this.evolutionService = evolutionService;
        this.consentCache = consentCache;
        this.idempotencyRepository = idempotencyRepository;
        this.auditService = auditService;
    }

    /**
     * Applies a full-state consent submission. Concurrent submissions, revocations and
     * token issuance for the same (user, client) are serialized by the grant row lock,
     * so the outcome is unique and self-consistent.
     */
    @Transactional
    public EffectiveConsent submit(ConsentSubmitRequest request) {
        validate(request);

        // Deterministic binding to exactly one scope-definition version.
        ScopeVersionDefinition definition = scopeService.getDefinition(request.scopeVersion());
        Map<String, Decision> requested = parseDecisions(request, definition);
        String requestHash = hash(request);

        ConsentGrantEntity grant = consentRepository.lockGrant(request.userId(), request.clientId());
        long now = System.currentTimeMillis();

        boolean contentChanged = false;
        if (grant == null) {
            grant = new ConsentGrantEntity(request.userId(), request.clientId(),
                    definition.version(), GrantState.ACTIVE, 1L, now, now);
            consentRepository.insertGrant(grant);
            contentChanged = true;
        } else {
            boolean versionChanged = !grant.scopeVersion().equals(definition.version());
            boolean revived = grant.state() == GrantState.REVOKED;
            long newSeq = grant.versionSeq() + ((versionChanged || revived) ? 1 : 0);
            grant = new ConsentGrantEntity(grant.userId(), grant.clientId(), definition.version(),
                    GrantState.ACTIVE, newSeq, grant.createdAt(), now);
            if (versionChanged || revived) {
                consentRepository.updateGrantVersion(grant);
                contentChanged = true;
            }
        }

        // Full-state convergence: upsert requested keys, remove keys no longer in the
        // pinned version so stale/retired decisions cannot linger or amplify.
        Map<String, Decision> stored = consentRepository.findDecisions(request.userId(), request.clientId());
        List<String> validKeys = definition.items().stream().map(i -> i.key()).toList();
        for (String validKey : validKeys) {
            Decision wanted = requested.get(validKey);
            Decision existing = stored.get(validKey);
            if (!wanted.equals(existing)) {
                consentRepository.upsertDecision(new ConsentDecisionEntity(
                        request.userId(), request.clientId(), validKey, wanted, now));
                contentChanged = true;
            }
        }
        for (String storedKey : stored.keySet()) {
            if (!requested.containsKey(storedKey)) {
                consentRepository.deleteDecision(request.userId(), request.clientId(), storedKey);
                contentChanged = true;
            }
        }

        // Idempotency bookkeeping and audit happen only after convergence. A replay of the
        // same (key, payload) must not duplicate rows nor emit additional side effects.
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            IdempotencyRecordEntity existing =
                    idempotencyRepository.find(request.idempotencyKey());
            if (existing != null && !existing.requestHash().equals(requestHash)) {
                throw new ConflictException("idempotency key already used with a different payload");
            }
            if (existing == null) {
                idempotencyRepository.tryInsert(new IdempotencyRecordEntity(
                        request.idempotencyKey(), request.userId(), request.clientId(),
                        requestHash, "CONSENT_SUBMIT", now));
            }
        }

        EffectiveConsent effective = resolveLocked(grant);
        if (contentChanged) {
            // A new converged state invalidates every cached view for this pair.
            consentCache.invalidate(request.userId(), request.clientId());
            auditService.record(request.userId(), request.userId(), request.clientId(),
                    "CONSENT_SUBMIT", AuditResult.ALLOWED,
                    "scopeVersion=" + definition.version() + " granted=" + effective.grantedKeys());
        }
        return effective;
    }

    /**
     * Reads the effective consent view. Authorization (self vs admin) is enforced by the
     * caller. Cache and database are coordinated so an invalidated version is never
     * returned as fresh.
     */
    public EffectiveConsent getEffective(String userId, String clientId, AuthContext actor) {
        ConsentGrantEntity grant = consentRepository.findGrant(userId, clientId);
        if (grant == null) {
            throw new NotFoundException("no consent for user=" + userId + " client=" + clientId);
        }
        ScopeVersionDefinition definition = scopeService.getDefinition(grant.scopeVersion());
        long now = System.currentTimeMillis();
        return consentCache.getOrLoad(userId, clientId, grant.scopeVersion(), now,
                () -> {
                    ConsentGrantEntity fresh = consentRepository.findGrant(userId, clientId);
                    Map<String, Decision> decisions =
                            consentRepository.findDecisions(userId, clientId);
                    return evolutionService.resolve(userId, clientId, definition,
                            fresh.scopeVersion(), decisions, fresh.state(), fresh.versionSeq());
                });
    }

    /** Builds the effective view assuming the grant row is already locked in this tx. */
    EffectiveConsent resolveLocked(ConsentGrantEntity grant) {
        ScopeVersionDefinition definition = scopeService.getDefinition(grant.scopeVersion());
        Map<String, Decision> decisions =
                consentRepository.findDecisions(grant.userId(), grant.clientId());
        return evolutionService.resolve(grant.userId(), grant.clientId(), definition,
                grant.scopeVersion(), decisions, grant.state(), grant.versionSeq());
    }

    private void validate(ConsentSubmitRequest request) {
        if (request == null || isBlank(request.userId()) || isBlank(request.clientId())) {
            throw new ValidationException("userId and clientId are required");
        }
        if (isBlank(request.scopeVersion())) {
            throw new ValidationException("scopeVersion is required to bind the request");
        }
        if (request.decisions() == null || request.decisions().isEmpty()) {
            throw new ValidationException("decisions must be the full item set, not empty");
        }
    }

    private Map<String, Decision> parseDecisions(ConsentSubmitRequest request,
                                                 ScopeVersionDefinition definition) {
        Map<String, Decision> parsed = new LinkedHashMap<>();
        for (ItemDecisionRequest item : request.decisions()) {
            if (item == null || isBlank(item.key()) || isBlank(item.decision())) {
                throw new ValidationException("each decision requires key and decision");
            }
            if (!definition.itemMap().containsKey(item.key())) {
                throw new ValidationException("item '" + item.key()
                        + "' does not exist in scope version " + definition.version());
            }
            Decision decision;
            try {
                decision = Decision.valueOf(item.decision().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ValidationException("decision must be GRANTED or DENIED: " + item.decision());
            }
            if (parsed.put(item.key(), decision) != null) {
                throw new ValidationException("duplicate decision for item: " + item.key());
            }
        }
        // Full-state requirement: every item of the pinned version must be addressed.
        for (String key : definition.itemMap().keySet()) {
            if (!parsed.containsKey(key)) {
                throw new ValidationException("missing decision for item: " + key
                        + " (full-state submission required for version " + definition.version() + ")");
            }
        }
        return parsed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String hash(ConsentSubmitRequest request) {
        StringBuilder sb = new StringBuilder(request.scopeVersion()).append('|');
        request.decisions().stream()
                .sorted(java.util.Comparator.comparing(ItemDecisionRequest::key))
                .forEach(d -> sb.append(d.key()).append('=')
                        .append(d.decision().trim().toUpperCase()).append(';'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
