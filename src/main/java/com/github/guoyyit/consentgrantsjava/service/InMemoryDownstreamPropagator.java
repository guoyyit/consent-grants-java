package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.exception.RevocationPropagationException;
import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated downstream credential-validity store that must learn about revocations.
 *
 * It is idempotent per token value, so retries after a partial failure never double-apply.
 * {@link #setFailNextN(int)} lets tests inject mid-flight downstream failures to prove the
 * system stays recoverable and converges on retry / restart.
 */
@Component
public class InMemoryDownstreamPropagator implements DownstreamTokenPropagator {

    private final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();
    private volatile int failNextN = 0;

    @Override
    public void propagateRevocation(AccessTokenEntity token) {
        int remaining = failNextN;
        if (remaining > 0) {
            // Best-effort decrement; under concurrency at least 'n' failures will surface.
            failNextN = remaining - 1;
            throw new RevocationPropagationException(
                    "simulated downstream failure for token " + token.tokenValue());
        }
        revokedTokens.add(token.tokenValue());
    }

    public boolean isRevoked(String tokenValue) {
        return revokedTokens.contains(tokenValue);
    }

    /** Test support: next n propagation calls fail. */
    public void setFailNextN(int n) {
        this.failNextN = Math.max(0, n);
    }

    /** Test support. */
    public int propagatedCount() {
        return revokedTokens.size();
    }

    /** Test support. */
    public void reset() {
        revokedTokens.clear();
        failNextN = 0;
    }
}
