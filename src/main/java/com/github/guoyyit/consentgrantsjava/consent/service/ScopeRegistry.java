package com.github.guoyyit.consentgrantsjava.consent.service;

import com.github.guoyyit.consentgrantsjava.consent.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.domain.ScopeVersion;
import com.github.guoyyit.consentgrantsjava.consent.store.ScopeVersionStore;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages scope definition versions and interprets historical consents under
 * version evolution (items added, split, or deprecated).
 */
@Service
public class ScopeRegistry {

    private final ScopeVersionStore store;
    private final ConsentCache cache;

    public ScopeRegistry(ScopeVersionStore store, ConsentCache cache) {
        this.store = store;
        this.cache = cache;
    }

    /**
     * Publishes a new immutable scope version. Versions must increase
     * monotonically. Publishing invalidates all cached consent views because
     * the interpretation of historical items may have changed.
     */
    public ScopeVersion publish(long version, Map<String, PermissionItem> items) {
        store.find(version).ifPresent(v -> {
            throw new IllegalArgumentException("scope version already published: " + version);
        });
        store.currentSafely().ifPresent(current -> {
            if (version <= current.version()) {
                throw new IllegalArgumentException(
                        "scope version must increase: " + version + " <= " + current.version());
            }
        });
        ScopeVersion published = store.publish(new ScopeVersion(version, items));
        cache.invalidateAll();
        return published;
    }

    /** The version in-flight authorization requests bind to by default. */
    public ScopeVersion current() {
        return store.current();
    }

    public ScopeVersion require(long version) {
        return store.find(version)
                .orElseThrow(() -> new IllegalArgumentException("unknown scope version: " + version));
    }

    /**
     * Maps items granted under an older version onto the given version.
     * ACTIVE items carry over; SPLIT items expand to their replacements
     * (recursively); DEPRECATED items and items unknown to the target version
     * are dropped — historical consent is never silently voided nor amplified.
     */
    public Set<String> migrate(Set<String> grantedItems, long fromVersion, long toVersion) {
        ScopeVersion target = require(toVersion);
        Set<String> result = new HashSet<>();
        for (String code : grantedItems) {
            resolve(code, target, result, new HashSet<>());
        }
        return result;
    }

    private void resolve(String code, ScopeVersion target, Set<String> out, Set<String> visiting) {
        if (!visiting.add(code)) {
            return; // cycle guard: a split chain must never loop
        }
        target.item(code).ifPresentOrElse(item -> {
            switch (item.status()) {
                case ACTIVE -> out.add(code);
                case SPLIT -> item.replacedBy().forEach(next -> resolve(next, target, out, visiting));
                case DEPRECATED -> {
                    // dropped: consent to a deprecated item grants nothing
                }
            }
        }, () -> {
            // unknown in target version: dropped, never amplified
        });
    }
}
