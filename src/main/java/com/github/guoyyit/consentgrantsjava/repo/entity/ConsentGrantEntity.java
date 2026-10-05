package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.GrantState;

public record ConsentGrantEntity(String userId,
                                 String clientId,
                                 String scopeVersion,
                                 GrantState state,
                                 long versionSeq,
                                 long createdAt,
                                 long updatedAt) {

    public ConsentGrantEntity withVersion(String newScopeVersion, GrantState newState, long seq, long now) {
        return new ConsentGrantEntity(userId, clientId, newScopeVersion, newState, seq, createdAt, now);
    }
}
