package com.github.guoyyit.consentgrantsjava.exception;

/**
 * Test-injectable downstream failure. Thrown by a propagation endpoint bean to
 * simulate mid-flight downstream failure; the system must converge on retry/restart.
 */
public class RevocationPropagationException extends RuntimeException {
    public RevocationPropagationException(String message) {
        super(message);
    }
}
