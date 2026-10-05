package com.github.guoyyit.consentgrantsjava.web.dto;

import com.github.guoyyit.consentgrantsjava.repo.entity.AuditEventEntity;

public record AuditView(long id,
                        long eventTime,
                        String actor,
                        String subjectUser,
                        String clientId,
                        String action,
                        String result,
                        String detail) {

    public static AuditView from(AuditEventEntity e) {
        return new AuditView(e.id(), e.eventTime(), e.actor(), e.subjectUser(), e.clientId(),
                e.action(), e.result().name(), e.detail());
    }
}
