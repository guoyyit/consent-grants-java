package com.github.guoyyit.consentgrantsjava.service;

import com.github.guoyyit.consentgrantsjava.cache.ConsentCache;
import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;
import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.exception.NotFoundException;
import com.github.guoyyit.consentgrantsjava.exception.ValidationException;
import com.github.guoyyit.consentgrantsjava.repo.ScopeDefinitionRepository;
import com.github.guoyyit.consentgrantsjava.repo.entity.ScopeItemEntity;
import com.github.guoyyit.consentgrantsjava.repo.entity.ScopeVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ScopeDefinitionService {

    private final ScopeDefinitionRepository repository;
    private final ConsentCache consentCache;

    public ScopeDefinitionService(ScopeDefinitionRepository repository, ConsentCache consentCache) {
        this.repository = repository;
        this.consentCache = consentCache;
    }

    public ScopeVersionDefinition getDefinition(String version) {
        ScopeVersionDefinition definition = repository.loadDefinition(version);
        if (definition == null) {
            throw new NotFoundException("scope version not found: " + version);
        }
        return definition;
    }

    public ScopeVersionDefinition latestDefinition() {
        List<String> versions = repository.findAllVersions();
        if (versions.isEmpty()) {
            throw new NotFoundException("no scope versions defined");
        }
        return getDefinition(versions.get(versions.size() - 1));
    }

    public String latestVersion() {
        return latestDefinition().version();
    }

    public List<String> versions() {
        return repository.findAllVersions();
    }

    public boolean exists(String version) {
        return repository.findVersion(version) != null;
    }

    /**
     * Publishes a new immutable version. Publishing a never-before-seen version flushes
     * cached consent views so that readers cannot conclude against a stale catalogue.
     * Re-publishing an existing version is a no-op and never mutates historical items.
     */
    @Transactional
    public ScopeVersionDefinition publish(ScopeVersionDefinition definition) {
        validate(definition);
        if (repository.findVersion(definition.version()) != null) {
            return repository.loadDefinition(definition.version());
        }
        long now = System.currentTimeMillis();
        List<ScopeItemEntity> entities = definition.items().stream()
                .map(i -> new ScopeItemEntity(definition.version(), i.key(), i.status(),
                        i.replaces(),
                        i.splitInto().isEmpty() ? null : String.join(",", i.splitInto()),
                        i.displayName()))
                .toList();
        repository.upsertVersion(new ScopeVersionEntity(definition.version(), now, true), entities);
        // A catalogue change must not leave stale conclusions readable anywhere.
        consentCache.invalidateAll();
        return repository.loadDefinition(definition.version());
    }

    private void validate(ScopeVersionDefinition definition) {
        if (definition == null || definition.version() == null || definition.version().isBlank()) {
            throw new ValidationException("scope version is required");
        }
        if (definition.items().isEmpty()) {
            throw new ValidationException("scope version " + definition.version() + " must contain items");
        }
        Set<String> keys = new HashSet<>();
        for (ScopeItemDef item : definition.items()) {
            if (item.key() == null || item.key().isBlank() || item.status() == null) {
                throw new ValidationException("each scope item requires key and status");
            }
            if (!keys.add(item.key())) {
                throw new ValidationException("duplicate scope item key in version "
                        + definition.version() + ": " + item.key());
            }
        }
    }
}
