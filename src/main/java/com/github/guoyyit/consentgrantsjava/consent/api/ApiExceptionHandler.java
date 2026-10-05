package com.github.guoyyit.consentgrantsjava.consent.api;

import com.github.guoyyit.consentgrantsjava.consent.service.InvalidScopeItemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps service failures to distinguishable HTTP results, so stale-version
 * submissions are visibly different from generic bad requests.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidScopeItemException.class)
    public ResponseEntity<Map<String, Object>> invalidScopeItem(InvalidScopeItemException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "INVALID_SCOPE_ITEM",
                "scopeVersion", e.scopeVersion(),
                "rejectedItems", e.rejectedItems(),
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", e.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "FORBIDDEN",
                "message", e.getMessage()));
    }
}
