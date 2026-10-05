package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.repo.entity.AccessTokenEntity;

/**
 * Represents the downstream credential-validity store that must learn about
 * revocations. Implementations must be idempotent per token value so that
 * retries after partial failure never double-apply.
 */
public interface DownstreamTokenPropagator {

    void propagateRevocation(AccessTokenEntity token);
}
