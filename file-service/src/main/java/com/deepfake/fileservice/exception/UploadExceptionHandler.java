package com.deepfake.fileservice.exception;

import com.deepfake.fileservice.controller.FileUploadController;
import com.deepfake.fileservice.dto.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@Order(-1)
@RestControllerAdvice(assignableTypes = FileUploadController.class)
public class UploadExceptionHandler {
    @ExceptionHandler(MediaProbeUnavailableException.class)
    public ResponseEntity<ErrorResponse> probeUnavailable(MediaProbeUnavailableException error) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(new ErrorResponse("SERVICE_UNAVAILABLE",
                        "Serwer jest chwilowo przeciążony. Spróbuj ponownie za chwilę.", null,
                        correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }

    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> quota(StorageQuotaExceededException error) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "STORAGE_QUOTA_EXCEEDED", error.getMessage(), null,
                correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }
}
