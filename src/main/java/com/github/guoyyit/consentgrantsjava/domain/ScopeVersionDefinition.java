package com.github.guoyyit.consentgrantsjava.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** An immutable, named revision of the complete permission catalogue. */
public record ScopeVersionDefinition(String version, List<ScopeItemDef> items) {

    public ScopeVersionDefinition {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public Map<String, ScopeItemDef> itemMap() {
        return items.stream().collect(Collectors.toUnmodifiableMap(ScopeItemDef::key, Function.identity()));
    }
}
