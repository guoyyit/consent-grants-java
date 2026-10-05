package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, versioned scope definition. Consents and in-flight authorization
 * requests are deterministically bound to exactly one version.
 *
 * @param version monotonically increasing version number
 * @param items   permission items keyed by code
 */
public record ScopeVersion(long version, Map<String, PermissionItem> items) {

    public ScopeVersion {
        items = items == null ? Map.of() : new LinkedHashMap<>(items);
    }

    public Optional<PermissionItem> item(String code) {
        return Optional.ofNullable(items.get(code));
    }

    public boolean contains(String code) {
        return items.containsKey(code);
    }
}
