package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.domain.PropagationState;
import com.github.guoyyit.consentgrantsjava.domain.TokenState;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AccessTokenRepository {

    private static final RowMapper<AccessTokenEntity> MAPPER = (rs, n) ->
            new AccessTokenEntity(rs.getString("token_value"),
                    rs.getString("user_id"),
                    rs.getString("client_id"),
                    rs.getString("scope_version"),
                    rs.getString("scope_keys"),
                    rs.getLong("issued_at"),
                    rs.getLong("expires_at"),
                    TokenState.valueOf(rs.getString("state")),
                    PropagationState.valueOf(rs.getString("propagation")),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    (Long) rs.getObject("revocation_id"));

    private final JdbcTemplate jdbc;

    public AccessTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccessTokenEntity token) {
        jdbc.update("INSERT INTO access_token(token_value, user_id, client_id, scope_version, scope_keys, " +
                        "issued_at, expires_at, state, propagation, attempts, last_error, revocation_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                token.tokenValue(), token.userId(), token.clientId(), token.scopeVersion(),
                token.scopeKeysCsv(), token.issuedAt(), token.expiresAt(), token.state().name(),
                token.propagation().name(), token.attempts(), token.lastError(), token.revocationId());
    }

    public AccessTokenEntity findByValue(String tokenValue) {
        List<AccessTokenEntity> result = jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE token_value = ?",
                MAPPER, tokenValue);
        return result.isEmpty() ? null : result.get(0);
    }

    /** Locks the token row for read-then-write propagation. */
    public AccessTokenEntity lockByValue(String tokenValue) {
        List<AccessTokenEntity> result = jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE token_value = ? FOR UPDATE",
                MAPPER, tokenValue);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<AccessTokenEntity> findActiveByUserClient(String userId, String clientId) {
        return jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE user_id = ? AND client_id = ? AND state = ? ORDER BY issued_at",
                MAPPER, userId, clientId, TokenState.ACTIVE.name());
    }

    /** Locks active tokens for one grant during revocation. */
    public List<AccessTokenEntity> lockActiveByUserClient(String userId, String clientId) {
        return jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE user_id = ? AND client_id = ? AND state = ? " +
                        "ORDER BY issued_at FOR UPDATE",
                MAPPER, userId, clientId, TokenState.ACTIVE.name());
    }

    public int updateRevocation(AccessTokenEntity token, TokenState state, PropagationState propagation,
                                Long revocationId, int attempts, String lastError) {
        return jdbc.update("UPDATE access_token SET state = ?, propagation = ?, revocation_id = ?, " +
                        "attempts = ?, last_error = ? WHERE token_value = ?",
                state.name(), propagation.name(), revocationId, attempts, lastError, token.tokenValue());
    }

    public List<AccessTokenEntity> findPendingPropagation(int limit) {
        return jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE propagation = ? ORDER BY issued_at LIMIT ?",
                MAPPER, PropagationState.PENDING.name(), limit);
    }

    public List<AccessTokenEntity> lockByRevocationId(long revocationId) {
        return jdbc.query(
                "SELECT token_value, user_id, client_id, scope_version, scope_keys, issued_at, expires_at, " +
                        "state, propagation, attempts, last_error, revocation_id " +
                        "FROM access_token WHERE revocation_id = ? " +
                        "ORDER BY token_value FOR UPDATE",
                MAPPER, revocationId);
    }

    public int countByRevocationIdAndPropagation(long revocationId, PropagationState propagation) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_token WHERE revocation_id = ? AND propagation = ?",
                Integer.class, revocationId, propagation.name());
        return count == null ? 0 : count;
    }
}
