package com.github.guoyyit.consentgrantsjava.domain;

import java.util.List;

/**
 * Definition of one permission item inside a scope-definition version.
 *
 * @param replaces    key of the predecessor item this one carries over / renames (nullable)
 * @param splitInto   successor keys when this item is split in a later version (nullable)
 */
public record ScopeItemDef(String key,
                           ItemStatus status,
                           String replaces,
                           List<String> splitInto,
                           String displayName) {
    public ScopeItemDef {
        splitInto = splitInto == null ? List.of() : List.copyOf(splitInto);
    }
}
