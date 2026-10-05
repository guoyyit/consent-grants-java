package com.github.guoyyit.consentgrantsjava.tests;

import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveItem;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.domain.ItemStatus;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.service.ScopeEvolutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** White-box tests of the explainable resolution rules themselves. */
class ScopeEvolutionResolutionTest extends AbstractIntegrationTest {

    @Autowired ScopeEvolutionService evolution;

    private EffectiveItem item(EffectiveConsent c, String key) {
        return c.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void equivalentRenameCarriesGrantWithoutAmplification() {
        // v1: legacy.read GRANTED. v2: legacy.read deprecated, replaced by modern.read.
        var v1 = new ScopeVersionDefinition("eq1", List.of(
                new ScopeItemDef("legacy.read", ItemStatus.ACTIVE, null, List.of(), "legacy")));
        var v2 = new ScopeVersionDefinition("eq2", List.of(
                new ScopeItemDef("legacy.read", ItemStatus.DEPRECATED, null, List.of(), "legacy"),
                new ScopeItemDef("modern.read", ItemStatus.ACTIVE, "legacy.read", List.of(), "modern")));
        // register both versions so the resolver can load the source definition
        register(v1, v2);

        EffectiveConsent resolved = evolution.resolve("u", "c", v2, "eq1",
                Map.of("legacy.read", Decision.GRANTED), GrantState.ACTIVE, 1);

        EffectiveItem modern = item(resolved, "modern.read");
        assertThat(modern.decision()).isEqualTo(Decision.GRANTED);
        assertThat(modern.source()).isEqualTo("MIGRATED_EQUIVALENT");
        // the identical deprecated key still shows its stored decision
        assertThat(item(resolved, "legacy.read").decision()).isEqualTo(Decision.GRANTED);
        assertThat(resolved.grantedKeys()).containsExactlyInAnyOrder("legacy.read", "modern.read");
    }

    @Test
    void splitNeverAutoGrantsSuccessorsEvenWhenPredecessorGranted() {
        var v1 = new ScopeVersionDefinition("sp1", List.of(
                new ScopeItemDef("aggregate.read", ItemStatus.ACTIVE, null, List.of(), "agg")));
        var v2 = new ScopeVersionDefinition("sp2", List.of(
                new ScopeItemDef("aggregate.read", ItemStatus.DEPRECATED, null,
                        List.of("a.read", "b.read"), "agg"),
                new ScopeItemDef("a.read", ItemStatus.ACTIVE, "aggregate.read", List.of(), "a"),
                new ScopeItemDef("b.read", ItemStatus.ACTIVE, "aggregate.read", List.of(), "b")));
        register(v1, v2);

        EffectiveConsent resolved = evolution.resolve("u", "c", v2, "sp1",
                Map.of("aggregate.read", Decision.GRANTED), GrantState.ACTIVE, 1);

        EffectiveItem a = item(resolved, "a.read");
        EffectiveItem b = item(resolved, "b.read");
        assertThat(a.source()).isEqualTo("SPLIT_AMBIGUOUS");
        assertThat(b.source()).isEqualTo("SPLIT_AMBIGUOUS");
        assertThat(a.decision()).isNull();
        assertThat(b.decision()).isNull();
        // Only the still-present predecessor key can appear granted; successors are not amplified.
        assertThat(resolved.grantedKeys()).containsExactly("aggregate.read");
    }

    @Test
    void retiredAndBrandNewItemsResolveToNoGrantButStayExplainable() {
        var v1 = new ScopeVersionDefinition("rt1", List.of(
                new ScopeItemDef("gone.read", ItemStatus.ACTIVE, null, List.of(), "gone"),
                new ScopeItemDef("keep.read", ItemStatus.ACTIVE, null, List.of(), "keep")));
        var v2 = new ScopeVersionDefinition("rt2", List.of(
                new ScopeItemDef("keep.read", ItemStatus.ACTIVE, null, List.of(), "keep"),
                new ScopeItemDef("fresh.read", ItemStatus.ACTIVE, null, List.of(), "fresh")));
        register(v1, v2);

        EffectiveConsent resolved = evolution.resolve("u", "c", v2, "rt1",
                Map.of("gone.read", Decision.GRANTED, "keep.read", Decision.GRANTED),
                GrantState.ACTIVE, 1);

        assertThat(item(resolved, "fresh.read").source()).isEqualTo("NEW_UNDECIDED");
        assertThat(item(resolved, "fresh.read").decision()).isNull();
        EffectiveItem gone = item(resolved, "gone.read");
        assertThat(gone.source()).isEqualTo("DROPPED_RETIRED");
        assertThat(gone.decision()).isNull();
        assertThat(resolved.grantedKeys()).containsExactly("keep.read");
    }

    @Test
    void revokedGrantResolvesToNoGrantsAtAll() {
        var v1 = new ScopeVersionDefinition("rv1", List.of(
                new ScopeItemDef("keep.read", ItemStatus.ACTIVE, null, List.of(), "keep")));
        register(v1);
        EffectiveConsent resolved = evolution.resolve("u", "c", v1, "rv1",
                Map.of("keep.read", Decision.GRANTED), GrantState.REVOKED, 3);
        assertThat(resolved.grantedKeys()).isEmpty();
        assertThat(resolved.state()).isEqualTo(GrantState.REVOKED);
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void register(ScopeVersionDefinition... definitions) {
        for (ScopeVersionDefinition def : definitions) {
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO scope_version(version, created_at, active) VALUES (?,?,?)",
                    def.version(), now, true);
            for (ScopeItemDef item : def.items()) {
                jdbc.update("INSERT INTO scope_item(version, item_key, status, replaces, split_into, display_name) " +
                                "VALUES (?,?,?,?,?,?)",
                        def.version(), item.key(), item.status().name(), item.replaces(),
                        item.splitInto().isEmpty() ? null : String.join(",", item.splitInto()),
                        item.displayName());
            }
        }
    }
}
