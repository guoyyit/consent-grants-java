package com.github.guoyyit.consentgrantsjava.web.dto;

import java.util.List;

public record TokenView(String token,
                        String userId,
                        String clientId,
                        String scopeVersion,
                        List<String> scopes,
                        long issuedAt,
                        long expiresAt,
                        String state) {
}
