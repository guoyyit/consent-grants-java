package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.Decision;

public record ConsentDecisionEntity(String userId,
                                    String clientId,
                                    String itemKey,
                                    Decision decision,
                                    long decidedAt) {
}
