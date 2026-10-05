package com.github.guoyyit.consentgrantsjava.domain;

import java.util.List;

/**
 * The uniquely interpretable consent outcome for one (user, client) pair,
 * resolved against a bound scope-definition version.
 */
public record EffectiveConsent(String userId,
                               String clientId,
                               String scopeVersion,
                               GrantState state,
                               long versionSeq,
                               List<EffectiveItem> items) {

    public EffectiveConsent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public List<String> grantedKeys() {
        return items.stream()
                .filter(i -> i.decision() == Decision.GRANTED)
                .map(EffectiveItem::key)
                .toList();
    }
}
