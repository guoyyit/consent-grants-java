package com.github.guoyyit.consentgrantsjava.web.dto;

import java.util.List;

/**
 * Full-state consent submission. scopeVersion pins the request deterministically;
 * decisions is the complete item set for that version.
 */
public record ConsentSubmitRequest(String userId,
                                   String clientId,
                                   String scopeVersion,
                                   List<ItemDecisionRequest> decisions,
                                   String idempotencyKey) {
}
