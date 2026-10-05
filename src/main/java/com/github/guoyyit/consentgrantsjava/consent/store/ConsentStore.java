package com.github.guoyyit.consentgrantsjava.consent.store;

import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Persistence for consent records, one converged record per (userId, clientId).
 * Per-key locks serialize submit/revoke/issue for the same pair so concurrent
 * operations produce a unique, self-consistent outcome.
 */
@Repository
public class ConsentStore {

    public record Key(String userId, String clientId) {
    }

    private final Map<Key, ConsentRecord> records = new ConcurrentHashMap<>();
    private final Map<Key, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Optional<ConsentRecord> find(String userId, String clientId) {
        return Optional.ofNullable(records.get(new Key(userId, clientId)));
    }

    public java.util.List<ConsentRecord> findByClient(String clientId) {
        return records.entrySet().stream()
                .filter(e -> e.getKey().clientId().equals(clientId))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * Atomically compute the next state for the pair under its exclusive lock.
     * The function receives the current record (or null) and returns the record
     * to store (never null).
     */
    public ConsentRecord mutate(String userId, String clientId,
                                Function<ConsentRecord, ConsentRecord> fn) {
        Key key = new Key(userId, clientId);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            ConsentRecord next = fn.apply(records.get(key));
            records.put(key, next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code fn} under the same per-pair lock used by {@link #mutate}, for
     * read-modify-write sequences that must serialize with submit/revoke but do
     * not themselves change the consent record (e.g. grant issuance).
     */
    public <T> T withLock(String userId, String clientId, Supplier<T> fn) {
        Key key = new Key(userId, clientId);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return fn.get();
        } finally {
            lock.unlock();
        }
    }
}
