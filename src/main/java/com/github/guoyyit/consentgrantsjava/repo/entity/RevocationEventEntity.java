package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;

public record RevocationEventEntity(long id,
                                    String userId,
                                    String clientId,
                                    RevocationStatus status,
                                    int attempts,
                                    String lastError,
                                    long createdAt,
                                    long updatedAt) {
}
