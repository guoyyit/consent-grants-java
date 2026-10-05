package com.github.guoyyit.consentgrantsjava.consent.service;

import java.util.Set;
import java.util.TreeSet;

/**
 * A submission referenced items that are not consentable in the bound scope
 * version (unknown, split, or deprecated). Distinguishes stale-version
 * submissions from valid ones.
 */
public class InvalidScopeItemException extends RuntimeException {

    private final long scopeVersion;
    private final Set<String> rejectedItems;

    public InvalidScopeItemException(long scopeVersion, Set<String> rejectedItems) {
        super("items not consentable in scope version " + scopeVersion + ": "
                + new TreeSet<>(rejectedItems));
        this.scopeVersion = scopeVersion;
        this.rejectedItems = Set.copyOf(rejectedItems);
    }

    public long scopeVersion() {
        return scopeVersion;
    }

    public Set<String> rejectedItems() {
        return rejectedItems;
    }
}
