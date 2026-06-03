package com.deepfake.orchestrator.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.dto.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps exceptions to the uniform {@link ErrorResponse} contract. OCP: a new exception type is a
 * new handler method, not an edit to existing ones. Pure mapping — no business logic here.
 *
 * <p>Note: 401/403 raised inside the security filter chain (missing/invalid token) are rendered by
 * Spring Security's entry point/handler before reaching here; the handlers below cover the cases
 * that surface during request dispatch (e.g. method-security {@code AccessDeniedException}).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String message = ex.getReason() != null ? ex.getReason() : codeOf(status);
        return body(status, codeOf(status), message, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", fields);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex); // stack trace stays in logs, not in the body
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", null);
    }

    private ResponseEntity<ErrorResponse> body(HttpStatusCode status, String code, String message,
                                               Map<String, String> fields) {
        ErrorResponse error = new ErrorResponse(code, message, fields, correlationId(), Instant.now());
        return ResponseEntity.status(status).body(error);
    }

    private static String codeOf(HttpStatusCode status) {
        return status instanceof HttpStatus hs ? hs.name() : String.valueOf(status.value());
    }

    private static String correlationId() {
        String cid = MDC.get("correlationId");
        return cid != null ? cid : UUID.randomUUID().toString();
    }
}
