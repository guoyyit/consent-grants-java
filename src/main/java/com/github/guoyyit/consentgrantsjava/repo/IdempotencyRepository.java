package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.repo.entity.IdempotencyRecordEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecordEntity> MAPPER = (rs, n) ->
            new IdempotencyRecordEntity(rs.getString("idem_key"),
                    rs.getString("user_id"),
                    rs.getString("client_id"),
                    rs.getString("request_hash"),
                    rs.getString("outcome"),
                    rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotencyRecordEntity find(String idemKey) {
        List<IdempotencyRecordEntity> result = jdbc.query(
                "SELECT idem_key, user_id, client_id, request_hash, outcome, created_at " +
                        "FROM idempotency_record WHERE idem_key = ?",
                MAPPER, idemKey);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Conditional insert. Returns false if a record with the same key already exists,
     * letting the caller distinguish a genuine first-submission from a replay.
     */
    public boolean tryInsert(IdempotencyRecordEntity record) {
        int rows = jdbc.update("INSERT INTO idempotency_record(idem_key, user_id, client_id, " +
                        "request_hash, outcome, created_at) SELECT ?,?,?,?,?,? " +
                        "WHERE NOT EXISTS (SELECT 1 FROM idempotency_record WHERE idem_key = ?)",
                record.idemKey(), record.userId(), record.clientId(), record.requestHash(),
                record.outcome(), record.createdAt(), record.idemKey());
        return rows > 0;
    }
}
