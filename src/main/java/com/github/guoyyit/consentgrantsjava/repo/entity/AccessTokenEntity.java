package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;

public record AccessTokenEntity(String tokenValue,
                                String userId,
                                String clientId,
                                String scopeVersion,
                                String scopeKeysCsv,
                                long issuedAt,
                                long expiresAt,
                                TokenState state,
                                PropagationState propagation,
                                int attempts,
                                String lastError,
                                Long revocationId) {

    public AccessTokenEntity withRevocation(TokenState newState, PropagationState newPropagation,
                                            Long revocationId, int attempts, String lastError) {
        return new AccessTokenEntity(tokenValue, userId, clientId, scopeVersion, scopeKeysCsv,
                issuedAt, expiresAt, newState, newPropagation, attempts, lastError, revocationId);
    }
}
