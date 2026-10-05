package com.github.guoyyit.consentgrantsjava.web.dto;

import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;

public record PropagationView(long id,
                              String userId,
                              String clientId,
                              String status,
                              int attempts,
                              String lastError) {

    public static PropagationView from(RevocationEventEntity e) {
        return new PropagationView(e.id(), e.userId(), e.clientId(), e.status().name(),
                e.attempts(), e.lastError());
    }
}
