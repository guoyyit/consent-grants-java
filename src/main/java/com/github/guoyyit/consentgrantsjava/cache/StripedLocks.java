package com.github.guoyyit.consentgrantsjava.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Read/write locks striped by (userId, clientId). Used to order cache
 * invalidation against concurrent reads so an invalidated entry is never
 * observed as "fresh" again.
 */
@Component
public class StripedLocks {

    private final ReentrantReadWriteLock[] stripes;

    public StripedLocks(@Value("${consent.cache.lock-stripes:64}") int stripeCount) {
        int n = Math.max(1, stripeCount);
        this.stripes = new ReentrantReadWriteLock[n];
        for (int i = 0; i < n; i++) {
            stripes[i] = new ReentrantReadWriteLock(true);
        }
    }

    private ReentrantReadWriteLock lockFor(String userId, String clientId) {
        return stripes[Math.floorMod((userId + "|" + clientId).hashCode(), stripes.length)];
    }

    public ReentrantReadWriteLock.ReadLock readLock(String userId, String clientId) {
        return lockFor(userId, clientId).readLock();
    }

    public ReentrantReadWriteLock.WriteLock writeLock(String userId, String clientId) {
        return lockFor(userId, clientId).writeLock();
    }
}
