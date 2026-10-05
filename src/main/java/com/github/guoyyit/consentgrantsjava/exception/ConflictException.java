package com.github.guoyyit.consentgrantsjava.exception;

/** Raised on an idempotency-key clash with a different request body. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
