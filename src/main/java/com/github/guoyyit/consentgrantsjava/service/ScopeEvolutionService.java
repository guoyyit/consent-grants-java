package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.domain.Decision;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import com.github.guoyyit.consentgrantsjava.domain.EffectiveItem;
import com.github.guoyyit.consentgrantsjava.domain.GrantState;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.repo.ScopeDefinitionRepository;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves stored decisions made against one scope-definition version into an
 * explainable effective view against the version the grant is pinned to.
 *
 * Compatibility rules (no silent loss, no amplification):
 * <ol>
 *   <li>Identical key present in both versions: stored decision carries over (EXPLICIT).</li>
 *   <li>Equivalent successor ({@code replaces} = predecessor key, predecessor not split):
 *       stored decision carries over and is labelled MIGRATED_EQUIVALENT.</li>
 *   <li>Predecessor was split into several successors: successors are never auto-granted
 *       (SPLIT_AMBIGUOUS), because granting all successors would amplify the permission;
 *       fresh explicit consent is required.</li>
 *   <li>Retired/dropped predecessor keys are retained in history but never grant
 *       (DROPPED_RETIRED); denial decisions survive so an item cannot reappear as granted.</li>
 *   <li>Brand-new keys have no decision and therefore no grant until explicitly consented.</li>
 * </ol>
 */
@Service
public class ScopeEvolutionService {

    private final ScopeDefinitionRepository repository;

    public ScopeEvolutionService(ScopeDefinitionRepository repository) {
        this.repository = repository;
    }

    public EffectiveConsent resolve(String userId, String clientId,
                                    ScopeVersionDefinition target,
                                    String decisionsVersion,
                                    Map<String, Decision> storedDecisions,
                                    GrantState state,
                                    long versionSeq) {
        Map<String, ScopeItemDef> targetItems = target.itemMap();

        ScopeVersionDefinition source = null;
        if (decisionsVersion != null && !decisionsVersion.equals(target.version())) {
            source = repository.loadDefinition(decisionsVersion);
        }
        Map<String, ScopeItemDef> sourceItems =
                source == null ? Map.of() : source.itemMap();

        // Keys that were split in source -> their successors must not be auto-migrated.
        Set<String> splitPredecessors = new HashSet<>();
        for (ScopeItemDef def : sourceItems.values()) {
            if (def.splitInto() != null && !def.splitInto().isEmpty()) {
                splitPredecessors.add(def.key());
            }
        }
        // A predecessor that is replaced by *multiple* target items is a split too, even if
        // the source document does not enumerate splitInto (e.g. children point back via replaces).
        java.util.Map<String, Integer> predecessorFanOut = new java.util.HashMap<>();
        for (ScopeItemDef def : targetItems.values()) {
            if (def.replaces() != null) {
                predecessorFanOut.merge(def.replaces(), 1, Integer::sum);
            }
        }
        predecessorFanOut.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .forEach(splitPredecessors::add);

        // Reverse index: successor key -> predecessor key it replaces.
        // A successor that is one of multiple split children stays ambiguous.
        java.util.Map<String, String> equivalentPredecessor = new java.util.HashMap<>();
        for (ScopeItemDef def : targetItems.values()) {
            String predecessor = def.replaces();
            if (predecessor != null && !splitPredecessors.contains(predecessor)) {
                equivalentPredecessor.put(def.key(), predecessor);
            }
        }

        List<EffectiveItem> output = new ArrayList<>();
        for (ScopeItemDef item : target.items()) {
            String key = item.key();

            // Rule 1: identical key.
            if (storedDecisions.containsKey(key)) {
                Decision decision = storedDecisions.get(key);
                output.add(new EffectiveItem(key, decision, "EXPLICIT",
                        "explicit decision stored on version " + decisionsVersion));
                continue;
            }

            // Rule 2: equivalent renamed successor (no split involved).
            String predecessor = equivalentPredecessor.get(key);
            if (predecessor != null && storedDecisions.containsKey(predecessor)) {
                Decision inherited = storedDecisions.get(predecessor);
                output.add(new EffectiveItem(key, inherited, "MIGRATED_EQUIVALENT",
                        "carried over from '" + predecessor + "' on version " + decisionsVersion
                                + " via equivalent replacement"));
                continue;
            }

            // Rule 3: successor of a split -> never auto-grant, even if predecessor was granted.
            if (item.replaces() != null && splitPredecessors.contains(item.replaces())) {
                Decision predecessorDecision = storedDecisions.get(item.replaces());
                output.add(new EffectiveItem(key, null, "SPLIT_AMBIGUOUS",
                        "'" + item.replaces() + "' was split into multiple scopes on version "
                                + target.version()
                                + "; predecessor decision (" + predecessorDecision
                                + ") is not amplified, fresh consent required"));
                continue;
            }

            // Rule 5: brand-new item, no historical decision at all.
            String note = "no decision recorded"
                    + (decisionsVersion == null ? "" : " as of version " + decisionsVersion);
            output.add(new EffectiveItem(key, null, "NEW_UNDECIDED", note));
        }

        // Rule 4: historical keys that no longer exist in the target version.
        // Retained for explainability; they can never produce a grant in the target view.
        if (source != null) {
            for (ScopeItemDef oldItem : source.items()) {
                String oldKey = oldItem.key();
                if (targetItems.containsKey(oldKey)) {
                    continue; // still represented above
                }
                boolean hasSuccessorReplacement = targetItems.values().stream()
                        .anyMatch(t -> oldKey.equals(t.replaces()));
                if (hasSuccessorReplacement) {
                    continue; // represented as MIGRATED or SPLIT entries above
                }
                Decision decision = storedDecisions.get(oldKey);
                output.add(new EffectiveItem(oldKey, null, "DROPPED_RETIRED",
                        "key existed on version " + decisionsVersion + " (" + oldItem.status()
                                + ") but has no successor on version " + target.version()
                                + "; historical decision " + decision
                                + " is retained for audit and is never granted"));
            }
        }

        // A revoked grant exposes its history but resolves to no grants at all.
        if (state == GrantState.REVOKED) {
            output = neutralize(output, "grant revoked; all access denied. ");
        }

        return new EffectiveConsent(userId, clientId, target.version(), state, versionSeq, output);
    }

    private List<EffectiveItem> neutralize(List<EffectiveItem> items, String prefix) {
        List<EffectiveItem> out = new ArrayList<>();
        for (EffectiveItem i : items) {
            out.add(new EffectiveItem(i.key(), null, i.source(), prefix + i.note()));
        }
        return out;
    }
}
