package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;
import com.github.guoyyit.consentgrantsjava.exception.NotFoundException;
import com.github.guoyyit.consentgrantsjava.repo.AccessTokenRepository;
import com.github.guoyyit.consentgrantsjava.repo.ConsentRepository;
import com.github.guoyyit.consentgrantsjava.repo.RevocationRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RevocationService {

    private final ConsentRepository consentRepository;
    private final AccessTokenRepository tokenRepository;
    private final RevocationRepository revocationRepository;
    private final ConsentCache consentCache;
    private final AuditService auditService;

    public RevocationService(ConsentRepository consentRepository,
                             AccessTokenRepository tokenRepository,
                             RevocationRepository revocationRepository,
                             ConsentCache consentCache,
                             AuditService auditService) {
        this.consentRepository = consentRepository;
        this.tokenRepository = tokenRepository;
        this.revocationRepository = revocationRepository;
        this.consentCache = consentCache;
        this.auditService = auditService;
    }

    /**
     * Revokes consent for one (user, client). The grant row lock serializes this against
     * concurrent issuance: a token issuance that acquires the lock afterwards observes
     * REVOKED and fails; an issuance that committed first is captured and revoked here.
     *
     * Downstream propagation is recorded durably as part of the same transaction. If a
     * downstream call later fails or the process restarts, the pending tokens and the
     * pending revocation event are retried from the database until convergence.
     */
    @Transactional
    public RevocationEventEntity revoke(String userId, String clientId, String actor) {
        long now = System.currentTimeMillis();
        ConsentGrantEntity grant = consentRepository.lockGrant(userId, clientId);
        if (grant == null) {
            throw new NotFoundException("no consent for user=" + userId + " client=" + clientId);
        }

        RevocationEventEntity event = new RevocationEventEntity(0L, userId, clientId,
                RevocationStatus.PENDING, 0, null, now, now);
        long eventId = revocationRepository.insert(event);

        boolean hadActiveTokens = false;
        // Locking the token rows together with the grant row makes the state transition
        // atomic: there is no window where consent is revoked but a token still validates.
        List<AccessTokenEntity> activeTokens =
                tokenRepository.lockActiveByUserClient(userId, clientId);
        for (AccessTokenEntity token : activeTokens) {
            tokenRepository.updateRevocation(token, TokenState.REVOKED, PropagationState.PENDING,
                    eventId, 0, null);
            hadActiveTokens = true;
        }

        int updated = consentRepository.markRevoked(grant, now);
        // updated==0 means it was already REVOKED; repeated revocation must still be safe.
        consentCache.invalidate(userId, clientId);

        if (!hadActiveTokens) {
            // Nothing downstream to converge: mark the event already propagated.
            revocationRepository.updateStatus(eventId, RevocationStatus.PROPAGATED, 0, null, now);
            event = new RevocationEventEntity(eventId, userId, clientId,
                    RevocationStatus.PROPAGATED, 0, null, now, now);
        } else {
            event = revocationRepository.findById(eventId);
        }

        if (updated > 0) {
            auditService.record(actor, userId, clientId, "CONSENT_REVOKE", AuditResult.ALLOWED,
                    "activeTokens=" + activeTokens.size());
        }
        return event;
    }
}
