package com.github.guoyyit.consentgrantsjava.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.service.ScopeDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds canonical scope-definition versions from the configured JSON document.
 * Seeding is idempotent: existing versions and their history are never overwritten,
 * so historical consents stay bound to the definition they were given against.
 */
@Component
public class ScopeDefinitionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScopeDefinitionSeeder.class);

    private final ScopeDefinitionService scopeService;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String location;

    public ScopeDefinitionSeeder(ScopeDefinitionService scopeService,
                                 ResourceLoader resourceLoader,
                                 ObjectMapper objectMapper,
                                 @Value("${consent.scope-definition.location:classpath:scope-definitions.json}")
                                 String location) {
        this.scopeService = scopeService;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.location = location;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.info("No scope-definition document at {}; skipping seed", location);
            return;
        }
        List<ScopeVersionDefinition> definitions =
                objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
                });
        for (ScopeVersionDefinition definition : definitions) {
            // publish() is idempotent and preserves already-stored versions.
            scopeService.publish(definition);
            log.info("Seeded scope definition version {}", definition.version());
        }
    }
}
