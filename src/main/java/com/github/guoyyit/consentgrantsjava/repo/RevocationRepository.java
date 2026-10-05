package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.domain.RevocationStatus;
import com.github.guoyyit.consentgrantsjava.repo.entity.RevocationEventEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class RevocationRepository {

    private static final RowMapper<RevocationEventEntity> MAPPER = (rs, n) ->
            new RevocationEventEntity(rs.getLong("id"),
                    rs.getString("user_id"),
                    rs.getString("client_id"),
                    RevocationStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public RevocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(RevocationEventEntity event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO revocation_event(user_id, client_id, status, attempts, last_error, " +
                            "created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.userId());
            ps.setString(2, event.clientId());
            ps.setString(3, event.status().name());
            ps.setInt(4, event.attempts());
            ps.setString(5, event.lastError());
            ps.setLong(6, event.createdAt());
            ps.setLong(7, event.updatedAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public RevocationEventEntity findById(long id) {
        List<RevocationEventEntity> result = jdbc.query(
                "SELECT id, user_id, client_id, status, attempts, last_error, created_at, updated_at " +
                        "FROM revocation_event WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    public int updateStatus(long id, RevocationStatus status, int attempts, String lastError, long now) {
        return jdbc.update("UPDATE revocation_event SET status = ?, attempts = ?, last_error = ?, updated_at = ? " +
                        "WHERE id = ?",
                status.name(), attempts, lastError, now, id);
    }

    public List<RevocationEventEntity> findPending(int limit) {
        return jdbc.query(
                "SELECT id, user_id, client_id, status, attempts, last_error, created_at, updated_at " +
                        "FROM revocation_event WHERE status = ? ORDER BY id LIMIT ? FOR UPDATE OF revocation_event",
                MAPPER, RevocationStatus.PENDING.name(), limit);
    }

    public List<RevocationEventEntity> findRecent(int limit) {
        return jdbc.query(
                "SELECT id, user_id, client_id, status, attempts, last_error, created_at, updated_at " +
                        "FROM revocation_event ORDER BY id DESC LIMIT ?",
                MAPPER, limit);
    }

    public List<RevocationEventEntity> findByUserClient(String userId, String clientId, int limit) {
        return jdbc.query(
                "SELECT id, user_id, client_id, status, attempts, last_error, created_at, updated_at " +
                        "FROM revocation_event WHERE user_id = ? AND client_id = ? ORDER BY id DESC LIMIT ?",
                MAPPER, userId, clientId, limit);
    }
}
