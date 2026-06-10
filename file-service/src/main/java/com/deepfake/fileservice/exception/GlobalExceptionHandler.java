package com.deepfake.fileservice.exception;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.deepfake.fileservice.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps exceptions to the uniform {@link ErrorResponse} contract. 401/403 from the security filter
 * chain are rendered by Spring Security before reaching here; these cover request dispatch.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Rejected upload -> 422, not 400/413.
    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFile(InvalidFileException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", ex.getMessage(), null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String message = ex.getReason() != null ? ex.getReason() : codeOf(status);
        return body(status, codeOf(status), message, null);
    }

    // Unparseable/malformed request body -> 400, not the catch-all 500. Generic message: the parser
    // detail (offset, field) stays in logs, never echoed to the client.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", null);
    }

    private ResponseEntity<ErrorResponse> body(HttpStatusCode status, String code, String message,
                                               java.util.Map<String, String> fields) {
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
