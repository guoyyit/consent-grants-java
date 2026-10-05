package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentDecisionEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ConsentGrantEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ConsentRepository {

    private static final RowMapper<ConsentGrantEntity> GRANT_MAPPER = (rs, n) ->
            new ConsentGrantEntity(rs.getString("user_id"),
                    rs.getString("client_id"),
                    rs.getString("scope_version"),
                    GrantState.valueOf(rs.getString("state")),
                    rs.getLong("version_seq"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));

    private static final RowMapper<ConsentDecisionEntity> DECISION_MAPPER = (rs, n) ->
            new ConsentDecisionEntity(rs.getString("user_id"),
                    rs.getString("client_id"),
                    rs.getString("item_key"),
                    Decision.valueOf(rs.getString("decision")),
                    rs.getLong("decided_at"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ConsentGrantEntity findGrant(String userId, String clientId) {
        List<ConsentGrantEntity> result = jdbc.query(
                "SELECT user_id, client_id, scope_version, state, version_seq, created_at, updated_at " +
                        "FROM consent_grant WHERE user_id = ? AND client_id = ?",
                GRANT_MAPPER, userId, clientId);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Acquires a transaction-scoped row lock on the grant. Must be called first inside
     * any transaction that reads-then-writes consent (submit / revoke / issue), which
     * serializes concurrent consent, revocation and issuance for one (user, client).
     */
    public ConsentGrantEntity lockGrant(String userId, String clientId) {
        List<ConsentGrantEntity> result = jdbc.query(
                "SELECT user_id, client_id, scope_version, state, version_seq, created_at, updated_at " +
                        "FROM consent_grant WHERE user_id = ? AND client_id = ? FOR UPDATE",
                GRANT_MAPPER, userId, clientId);
        return result.isEmpty() ? null : result.get(0);
    }

    public void insertGrant(ConsentGrantEntity grant) {
        jdbc.update("INSERT INTO consent_grant(user_id, client_id, scope_version, state, version_seq, " +
                        "created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                grant.userId(), grant.clientId(), grant.scopeVersion(), grant.state().name(),
                grant.versionSeq(), grant.createdAt(), grant.updatedAt());
    }

    public int updateGrantVersion(ConsentGrantEntity grant) {
        return jdbc.update("UPDATE consent_grant SET scope_version = ?, state = ?, version_seq = ?, " +
                        "updated_at = ? WHERE user_id = ? AND client_id = ?",
                grant.scopeVersion(), grant.state().name(), grant.versionSeq(), grant.updatedAt(),
                grant.userId(), grant.clientId());
    }

    public Map<String, Decision> findDecisions(String userId, String clientId) {
        Map<String, Decision> out = new LinkedHashMap<>();
        findDecisionEntities(userId, clientId).forEach(d -> out.put(d.itemKey(), d.decision()));
        return out;
    }

    public List<ConsentDecisionEntity> findDecisionEntities(String userId, String clientId) {
        return jdbc.query(
                "SELECT user_id, client_id, item_key, decision, decided_at " +
                        "FROM consent_item_decision WHERE user_id = ? AND client_id = ? ORDER BY item_key",
                DECISION_MAPPER, userId, clientId);
    }

    /** MERGE converges repeated submissions onto the same primary key: never a duplicate row. */
    public void upsertDecision(ConsentDecisionEntity decision) {
        jdbc.update("MERGE INTO consent_item_decision(user_id, client_id, item_key, decision, decided_at) " +
                        "KEY(user_id, client_id, item_key) VALUES (?,?,?,?,?)",
                decision.userId(), decision.clientId(), decision.itemKey(),
                decision.decision().name(), decision.decidedAt());
    }

    public void deleteDecision(String userId, String clientId, String itemKey) {
        jdbc.update("DELETE FROM consent_item_decision WHERE user_id = ? AND client_id = ? AND item_key = ?",
                userId, clientId, itemKey);
    }

    public int markRevoked(ConsentGrantEntity grant, long now) {
        return jdbc.update("UPDATE consent_grant SET state = ?, updated_at = ? " +
                        "WHERE user_id = ? AND client_id = ? AND state = ?",
                GrantState.REVOKED.name(), now, grant.userId(), grant.clientId(),
                GrantState.ACTIVE.name());
    }

    public List<ConsentGrantEntity> findGrantsForUser(String userId) {
        return jdbc.query(
                "SELECT user_id, client_id, scope_version, state, version_seq, created_at, updated_at " +
                        "FROM consent_grant WHERE user_id = ? ORDER BY client_id",
                GRANT_MAPPER, userId);
    }

    public List<ConsentGrantEntity> findAllGrants(int limit) {
        return jdbc.query(
                "SELECT user_id, client_id, scope_version, state, version_seq, created_at, updated_at " +
                        "FROM consent_grant ORDER BY updated_at DESC LIMIT ?",
                GRANT_MAPPER, limit);
    }
}
