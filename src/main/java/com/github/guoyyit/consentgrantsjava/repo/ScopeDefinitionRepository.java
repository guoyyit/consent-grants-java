package com.github.guoyyit.consentgrantsjava.repo;

import com.github.guoyyit.consentgrantsjava.domain.ItemStatus;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.repo.entity.ScopeItemEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ScopeVersionEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Repository
public class ScopeDefinitionRepository {

    private static final RowMapper<ScopeVersionEntity> VERSION_MAPPER = (rs, n) ->
            new ScopeVersionEntity(rs.getString("version"),
                    rs.getLong("created_at"),
                    rs.getBoolean("active"));

    private static final RowMapper<ScopeItemEntity> ITEM_MAPPER = (rs, n) ->
            new ScopeItemEntity(rs.getString("version"),
                    rs.getString("item_key"),
                    ItemStatus.valueOf(rs.getString("status")),
                    rs.getString("replaces"),
                    rs.getString("split_into"),
                    rs.getString("display_name"));

    private final JdbcTemplate jdbc;

    public ScopeDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent version upsert. A version that already exists is returned unchanged:
     * historical consents must remain bound to exactly the definition they were made
     * against, so an existing version's items are never rewritten.
     */
    @Transactional
    public ScopeVersionEntity upsertVersion(ScopeVersionEntity version, List<ScopeItemEntity> items) {
        ScopeVersionEntity existing = findVersion(version.version());
        if (existing == null) {
            jdbc.update("INSERT INTO scope_version(version, created_at, active) VALUES (?,?,?)",
                    version.version(), version.createdAt(), version.active());
            for (ScopeItemEntity item : items) {
                jdbc.update("INSERT INTO scope_item(version, item_key, status, replaces, split_into, display_name) " +
                                "VALUES (?,?,?,?,?,?)",
                        item.version(), item.itemKey(), item.status().name(),
                        item.replaces(), item.splitInto(), item.displayName());
            }
            return version;
        }
        return existing;
    }

    public ScopeVersionEntity findVersion(String version) {
        List<ScopeVersionEntity> result = jdbc.query(
                "SELECT version, created_at, active FROM scope_version WHERE version = ?",
                VERSION_MAPPER, version);
        return result.isEmpty() ? null : result.get(0);
    }

    public List<String> findAllVersions() {
        return jdbc.queryForList(
                "SELECT version FROM scope_version ORDER BY created_at ASC, version ASC",
                String.class);
    }

    public List<ScopeItemEntity> findItems(String version) {
        return jdbc.query(
                "SELECT version, item_key, status, replaces, split_into, display_name " +
                        "FROM scope_item WHERE version = ? ORDER BY item_key ASC",
                ITEM_MAPPER, version);
    }

    public ScopeVersionDefinition loadDefinition(String version) {
        ScopeVersionEntity v = findVersion(version);
        if (v == null) {
            return null;
        }
        List<ScopeItemDef> defs = findItems(version).stream()
                .map(i -> new ScopeItemDef(i.itemKey(), i.status(), i.replaces(),
                        i.splitInto() == null || i.splitInto().isBlank()
                                ? List.of()
                                : Arrays.stream(i.splitInto().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                        i.displayName()))
                .toList();
        return new ScopeVersionDefinition(version, defs);
    }
}
