package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import com.github.guoyyit.consentgrantsjava.repo.entity.AuditEventEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AuditRepository {

    private static final RowMapper<AuditEventEntity> MAPPER = (rs, n) ->
            new AuditEventEntity(rs.getLong("id"),
                    rs.getLong("event_time"),
                    rs.getString("actor"),
                    rs.getString("subject_user"),
                    rs.getString("client_id"),
                    rs.getString("action"),
                    AuditResult.valueOf(rs.getString("result")),
                    rs.getString("detail"));

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEventEntity event) {
        jdbc.update("INSERT INTO audit_event(event_time, actor, subject_user, client_id, action, result, detail) " +
                        "VALUES (?,?,?,?,?,?,?)",
                event.eventTime(), event.actor(), event.subjectUser(), event.clientId(),
                event.action(), event.result().name(), event.detail());
    }

    public List<AuditEventEntity> findRecent(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 1000);
        return jdbc.query(
                "SELECT id, event_time, actor, subject_user, client_id, action, result, detail " +
                        "FROM audit_event ORDER BY id DESC LIMIT ?",
                MAPPER, bounded);
    }

    public List<AuditEventEntity> findBySubjectUser(String userId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 1000);
        return jdbc.query(
                "SELECT id, event_time, actor, subject_user, client_id, action, result, detail " +
                        "FROM audit_event WHERE subject_user = ? ORDER BY id DESC LIMIT ?",
                MAPPER, userId, bounded);
    }
}
