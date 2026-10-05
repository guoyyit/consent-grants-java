package com.github.guoyyit.consentgrantsjava.exception;

/** Raised when a caller is not authorized to read the targeted consent detail. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
