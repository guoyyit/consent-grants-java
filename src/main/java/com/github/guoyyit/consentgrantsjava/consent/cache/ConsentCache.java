package com.github.guoyyit.consentgrantsjava.consent.cache;

import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Read-through cache for consent records.
 *
 * <p>Consistency protocol (seqlock-style): writers mutate the store first and
 * bump an epoch afterwards; readers snapshot the epoch before and after the
 * store load. If the epoch changed during the load, the loaded value may be
 * stale and is discarded in favour of a fresh read. Cached entries carry the
 * epoch they were loaded under and are rejected once any invalidation bumped
 * the epoch — so a concurrent invalidation racing with a read can never yield
 * a stale conclusion.
 */
@Component
public class ConsentCache {

    private record Key(String userId, String clientId) {
    }

    private record Entry(Optional<ConsentRecord> value, long globalEpoch, long keyEpoch) {
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalEpoch = new AtomicLong();
    private final Map<Key, AtomicLong> keyEpochs = new ConcurrentHashMap<>();

    /**
     * Returns the consent for the pair, loading through {@code loader} on miss
     * or after any invalidation.
     */
    public Optional<ConsentRecord> get(String userId, String clientId,
                                       Supplier<Optional<ConsentRecord>> loader) {
        Key key = new Key(userId, clientId);
        long g1 = globalEpoch.get();
        long k1 = keyEpoch(key);

        Entry cached = entries.get(key);
        if (cached != null && cached.globalEpoch() == g1 && cached.keyEpoch() == k1) {
            return cached.value();
        }

        Optional<ConsentRecord> loaded = loader.get();
        long g2 = globalEpoch.get();
        long k2 = keyEpoch(key);
        if (g1 != g2 || k1 != k2) {
            // An invalidation overlapped the load: the value may be stale.
            // Re-read after the invalidation completed and do not cache.
            return loader.get();
        }
        entries.put(key, new Entry(loaded, g2, k2));
        return loaded;
    }

    /** Invalidates the cached entry for the pair (scope change or revocation). */
    public void invalidate(String userId, String clientId) {
        Key key = new Key(userId, clientId);
        keyEpochs.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        entries.remove(key);
    }

    /** Drops every cached entry (e.g. when a new scope version is published). */
    public void invalidateAll() {
        globalEpoch.incrementAndGet();
        entries.clear();
    }

    private long keyEpoch(Key key) {
        AtomicLong epoch = keyEpochs.get(key);
        return epoch == null ? 0L : epoch.get();
    }
}
