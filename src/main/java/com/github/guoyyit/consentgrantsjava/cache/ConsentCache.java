package com.github.guoyyit.consentgrantsjava.cache;

import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Effective-consent cache with TTL.
 *
 * Consistency guarantee: each (user, client) has a striped read/write lock. A cache hit
 * holds the read lock only while validating freshness against the clock; invalidation
 * holds the write lock while removing every versioned entry for that pair. Because a
 * write lock excludes all readers, once {@link #invalidate} returns, no subsequent read
 * can observe the removed entry as fresh (an in-flight read may finish first, but any
 * read that starts after invalidation loads from the database).
 */
@Component
public class ConsentCache {

    private record Entry(EffectiveConsent value, long expiresAt) {
        boolean fresh(long now) {
            return now < expiresAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final StripedLocks locks;
    private final boolean enabled;
    private final long ttlMillis;

    public ConsentCache(StripedLocks locks,
                        @Value("${consent.cache.enabled:true}") boolean enabled,
                        @Value("${consent.cache.ttl-ms:60000}") long ttlMillis) {
        this.locks = locks;
        this.enabled = enabled;
        this.ttlMillis = ttlMillis;
    }

    private static String key(String userId, String clientId, String version) {
        return userId + "|" + clientId + "|" + version;
    }

    private static String prefix(String userId, String clientId) {
        return userId + "|" + clientId + "|";
    }

    public Optional<EffectiveConsent> get(String userId, String clientId, String version, long now) {
        if (!enabled) {
            return Optional.empty();
        }
        var readLock = locks.readLock(userId, clientId);
        readLock.lock();
        try {
            Entry entry = store.get(key(userId, clientId, version));
            if (entry != null && entry.fresh(now)) {
                return Optional.of(entry.value());
            }
            return Optional.empty();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns a cached fresh value or loads one. Loading happens outside the read lock
     * to avoid blocking invalidation; the freshly loaded value is only published while
     * holding the read lock and after re-checking, so an invalidation that completed
     * during the load is never overwritten by the stale load.
     */
    public EffectiveConsent getOrLoad(String userId, String clientId, String version,
                                      long now, Supplier<EffectiveConsent> loader) {
        Optional<EffectiveConsent> cached = get(userId, clientId, version, now);
        if (cached.isPresent()) {
            return cached.get();
        }
        EffectiveConsent loaded = loader.get();
        if (!enabled) {
            return loaded;
        }
        var readLock = locks.readLock(userId, clientId);
        readLock.lock();
        try {
            // Re-check under lock: an invalidate() that ran during the DB load takes the
            // write lock and completes before we (re)acquire the read lock here.
            Entry concurrent = store.get(key(userId, clientId, version));
            if (concurrent != null && concurrent.fresh(System.currentTimeMillis())) {
                return concurrent.value();
            }
            store.put(key(userId, clientId, version),
                    new Entry(loaded, System.currentTimeMillis() + ttlMillis));
            return loaded;
        } finally {
            readLock.unlock();
        }
    }

    public void put(String userId, String clientId, String version, EffectiveConsent value, long now) {
        if (!enabled) {
            return;
        }
        var writeLock = locks.writeLock(userId, clientId);
        writeLock.lock();
        try {
            store.put(key(userId, clientId, version), new Entry(value, now + ttlMillis));
        } finally {
            writeLock.unlock();
        }
    }

    /** Removes every cached version for one (user, client); blocks concurrent freshness checks. */
    public void invalidate(String userId, String clientId) {
        var writeLock = locks.writeLock(userId, clientId);
        writeLock.lock();
        try {
            String p = prefix(userId, clientId);
            store.keySet().removeIf(k -> k.startsWith(p));
        } finally {
            writeLock.unlock();
        }
    }

    /** Catalogue-wide invalidation after a scope-definition change. */
    public void invalidateAll() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    /** Test support: list cached entry keys. */
    Set<String> cachedKeys() {
        return Set.copyOf(store.keySet());
    }
}
