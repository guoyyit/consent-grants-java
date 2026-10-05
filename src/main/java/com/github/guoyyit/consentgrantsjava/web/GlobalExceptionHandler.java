package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.exception.ConflictException;
import com.github.guoyyit.consentgrantsjava.exception.ForbiddenException;
import com.github.guoyyit.consentgrantsjava.exception.NotFoundException;
import com.github.guoyyit.consentgrantsjava.exception.ValidationException;
import com.github.guoyyit.consentgrantsjava.exception.VersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(VersionConflictException e) {
        // Distinguishable result for a request bound to a different scope-definition version.
        return ResponseEntity.status(428).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> validation(ValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
}
