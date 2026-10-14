package com.deepfake.fileservice.exception;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

import org.springframework.beans.TypeMismatchException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    public ResponseEntity<ErrorResponse> handleInvalidFile(InvalidFileException ex, HttpServletRequest request) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", ex.getMessage(), null, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException ex, HttpServletRequest request) {
        if (isEventStream(request)) return ResponseEntity.status(ex.getStatusCode()).build();
        HttpStatusCode status = ex.getStatusCode();
        String message = statusMessage(status);
        return body(status, codeOf(status), message, null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Nieprawidłowe dane żądania.", fields, request);
    }

    // Unparseable/malformed request body -> 400, not the catch-all 500. Generic message: the parser
    // detail (offset, field) stays in logs, never echoed to the client.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Nieprawidłowa treść żądania.", null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Brak dostępu do zasobu.", null, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Wymagane zalogowanie.", null, request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Nie znaleziono zasobu.", null, request);
    }

    @ExceptionHandler({TypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, PropertyReferenceException.class})
    public ResponseEntity<ErrorResponse> handleInvalidParameter(Exception ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Nieprawidłowe parametry żądania.", null, request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        // Spring wraps every non-size parse failure here, including a full spool disk; keep it visible.
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            log.warn("Multipart request could not be parsed", ex);
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Nieprawidłowe parametry żądania.", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(ex.getHeaders())
                .body(acceptsJson(request) ? new ErrorResponse("METHOD_NOT_ALLOWED", "Niedozwolona metoda żądania.",
                        null, correlationId(), Instant.now()) : null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleContentType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(ex.getHeaders())
                .body(acceptsJson(request) ? new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "Nieobsługiwany typ zawartości żądania.",
                        null, correlationId(), Instant.now()) : null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        // The caller cannot accept our JSON error representation either.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).headers(ex.getHeaders()).build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Plik przekracza dopuszczalny rozmiar.", null, request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnected(AsyncRequestNotUsableException ex) {
        // The connection is already unusable; writing another response would fail again.
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private static boolean isEventStream(HttpServletRequest request) {
        // MVC removes producible media types before exception resolution.
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        return handler instanceof org.springframework.web.method.HandlerMethod method
                && org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class
                        .isAssignableFrom(method.getReturnType().getParameterType());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Wystąpił wewnętrzny błąd serwera.", null, request);
    }

    private ResponseEntity<ErrorResponse> body(HttpStatusCode status, String code, String message,
                                               Map<String, String> fields, HttpServletRequest request) {
        if (!acceptsJson(request)) return ResponseEntity.status(status).build();
        ErrorResponse error = new ErrorResponse(code, message, fields, correlationId(), Instant.now());
        return ResponseEntity.status(status).body(error);
    }

    private static String statusMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Nieprawidłowe żądanie.";
            case 401 -> "Wymagane zalogowanie.";
            case 403 -> "Brak dostępu do zasobu.";
            case 404 -> "Nie znaleziono zasobu.";
            case 409 -> "Nie można wykonać operacji w obecnym stanie zasobu.";
            case 413 -> "Plik przekracza dopuszczalny rozmiar.";
            case 429 -> "Zbyt wiele żądań. Spróbuj ponownie później.";
            case 503 -> "Usługa jest chwilowo niedostępna. Spróbuj ponownie później.";
            default -> "Nie udało się obsłużyć żądania.";
        };
    }

    private static boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) return true;
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> type.getQualityValue() > 0 && type.isCompatibleWith(MediaType.APPLICATION_JSON));
        } catch (org.springframework.http.InvalidMediaTypeException ex) {
            return false;
        }
    }

    private static String codeOf(HttpStatusCode status) {
        return status instanceof HttpStatus hs ? hs.name() : String.valueOf(status.value());
    }

    private static String correlationId() {
        String cid = MDC.get("correlationId");
        return cid != null ? cid : UUID.randomUUID().toString();
    }
}
