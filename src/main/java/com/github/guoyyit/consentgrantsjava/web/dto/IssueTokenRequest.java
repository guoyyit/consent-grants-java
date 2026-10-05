package com.github.guoyyit.consentgrantsjava.web.dto;

import java.util.List;

public record IssueTokenRequest(String userId,
                                String clientId,
                                String scopeVersion,
                                List<String> requestedScopes,
                                long ttlMillis) {
}
