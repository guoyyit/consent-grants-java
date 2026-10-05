package com.github.guoyyit.consentgrantsjava.repo.entity;

import com.github.guoyyit.consentgrantsjava.domain.ItemStatus;

public record ScopeItemEntity(String version,
                              String itemKey,
                              ItemStatus status,
                              String replaces,
                              String splitInto,
                              String displayName) {
}
