package com.github.guoyyit.consentgrantsjava.consent.domain;

/**
 * Lifecycle status of a permission item within a scope definition version.
 */
public enum PermissionItemStatus {
    /** Item can be consented to in this version. */
    ACTIVE,
    /** Item was split into finer-grained items; historical consents map to {@code replacedBy}. */
    SPLIT,
    /** Item no longer exists; historical consents to it are preserved but cannot be used. */
    DEPRECATED
}
