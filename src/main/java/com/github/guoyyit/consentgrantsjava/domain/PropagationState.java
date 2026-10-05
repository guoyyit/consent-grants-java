package com.github.guoyyit.consentgrantsjava.domain;

/** Downstream propagation state of a credential after revocation. */
public enum PropagationState {
    NOT_REQUIRED,
    PENDING,
    DONE,
    FAILED
}
