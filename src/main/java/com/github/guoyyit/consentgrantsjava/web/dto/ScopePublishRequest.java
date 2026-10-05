package com.github.guoyyit.consentgrantsjava.web.dto;

import com.github.guoyyit.consentgrantsjava.domain.ScopeItemDef;

import java.util.List;

public record ScopePublishRequest(String version, List<ScopeItemDef> items) {
}
