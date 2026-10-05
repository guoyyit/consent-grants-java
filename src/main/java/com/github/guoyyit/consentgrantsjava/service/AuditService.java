package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.repo.AuditRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.AuditEventEntity;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void record(String actor, String subjectUser, String clientId,
                       String action, AuditResult result, String detail) {
        String safeDetail = detail == null ? null
                : detail.substring(0, Math.min(detail.length(), 1000));
        auditRepository.insert(new AuditEventEntity(0L, System.currentTimeMillis(),
                actor, subjectUser, clientId, action, result, safeDetail));
    }

    public List<AuditEventEntity> recent(int limit) {
        return auditRepository.findRecent(limit);
    }

    public List<AuditEventEntity> forSubject(String userId, int limit) {
        return auditRepository.findBySubjectUser(userId, limit);
    }
}
