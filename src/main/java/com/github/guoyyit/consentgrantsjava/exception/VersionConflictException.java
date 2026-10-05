package com.github.guoyyit.consentgrantsjava.exception;

/**
 * Raised when a request must be deterministically pinned to a scope-definition version
 * but cannot (unknown version, or an in-flight request targets a non-current version
 * with semantics that cannot be auto-resolved).
 */
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(String message) {
        super(message);
    }
}
