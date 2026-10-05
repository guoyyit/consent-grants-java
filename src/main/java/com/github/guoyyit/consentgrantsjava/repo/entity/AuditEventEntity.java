package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;

public record AuditEventEntity(long id,
                               long eventTime,
                               String actor,
                               String subjectUser,
                               String clientId,
                               String action,
                               AuditResult result,
                               String detail) {
}
