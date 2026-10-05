package com.github.guoyyit.consentgrantsjava.repo.entity;

public record IdempotencyRecordEntity(String idemKey,
                                      String userId,
                                      String clientId,
                                      String requestHash,
                                      String outcome,
                                      long createdAt) {
}
