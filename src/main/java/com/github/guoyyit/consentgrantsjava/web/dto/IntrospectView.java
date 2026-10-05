package com.github.guoyyit.consentgrantsjava.web.dto;

import java.util.List;

public record IntrospectView(boolean active,
                             String userId,
                             String clientId,
                             String scopeVersion,
                             List<String> scopes,
                             String state,
                             String propagation) {
}
