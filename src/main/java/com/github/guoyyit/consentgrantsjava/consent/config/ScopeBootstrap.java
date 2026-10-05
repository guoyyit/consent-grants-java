package com.github.guoyyit.consentgrantsjava.consent.config;

import com.github.guoyyit.consentgrantsjava.consent.domain.PermissionItem;
import com.github.guoyyit.consentgrantsjava.consent.service.ScopeRegistry;
import com.github.guoyyit.consentgrantsjava.consent.store.ScopeVersionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the initial scope definition version on first boot, so in-flight
 * authorization requests always have a version to bind to.
 */
@Configuration
@EnableConfigurationProperties(ConsentProperties.class)
public class ScopeBootstrap {

    private final ScopeVersionStore store;
    private final ScopeRegistry registry;
    private final ConsentProperties properties;

    public ScopeBootstrap(ScopeVersionStore store, ScopeRegistry registry,
                          ConsentProperties properties) {
        this.store = store;
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void publishInitialVersion() {
        if (store.currentSafely().isPresent()) {
            return; // versions already exist (e.g. restored): never republish
        }
        Map<String, PermissionItem> items = new LinkedHashMap<>();
        for (String code : properties.getInitialItems()) {
            items.put(code, PermissionItem.active(code));
        }
        registry.publish(properties.getInitialVersion(), items);
    }
}
