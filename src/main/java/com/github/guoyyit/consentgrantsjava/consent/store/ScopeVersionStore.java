package com.github.guoyyit.consentgrantsjava.consent.store;

import com.github.guoyyit.consentgrantsjava.consent.domain.ScopeVersion;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable scope definition versions. Versions are append-only: once published
 * a version never changes, so a consent bound to a version stays interpretable.
 */
@Repository
public class ScopeVersionStore {

    private final Map<Long, ScopeVersion> versions = new ConcurrentHashMap<>();

    public ScopeVersion publish(ScopeVersion version) {
        ScopeVersion existing = versions.putIfAbsent(version.version(), version);
        if (existing != null) {
            throw new IllegalStateException("scope version already published: " + version.version());
        }
        return version;
    }

    public Optional<ScopeVersion> find(long version) {
        return Optional.ofNullable(versions.get(version));
    }

    public ScopeVersion current() {
        return currentSafely()
                .orElseThrow(() -> new IllegalStateException("no scope version published"));
    }

    public Optional<ScopeVersion> currentSafely() {
        return versions.values().stream()
                .max(Comparator.comparingLong(ScopeVersion::version));
    }
}
