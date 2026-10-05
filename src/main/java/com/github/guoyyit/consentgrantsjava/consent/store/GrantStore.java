package com.github.guoyyit.consentgrantsjava.consent.store;

import com.github.guoyyit.consentgrantsjava.consent.domain.Grant;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence for issued grants, indexed by id and by (userId, clientId).
 */
@Repository
public class GrantStore {

    private final Map<String, Grant> byId = new ConcurrentHashMap<>();

    public Grant save(Grant grant) {
        byId.put(grant.grantId(), grant);
        return grant;
    }

    public Optional<Grant> findById(String grantId) {
        return Optional.ofNullable(byId.get(grantId));
    }

    public Optional<Grant> findByToken(String token) {
        return byId.values().stream().filter(g -> g.token().equals(token)).findFirst();
    }

    public List<Grant> findByUserAndClient(String userId, String clientId) {
        return byId.values().stream()
                .filter(g -> g.userId().equals(userId) && g.clientId().equals(clientId))
                .toList();
    }
}
