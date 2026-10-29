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
    @ExceptionHandler({MediaProbeUnavailableException.class,
            software.amazon.awssdk.core.exception.SdkClientException.class,
            org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> probeUnavailable(RuntimeException error) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(new ErrorResponse("SERVICE_UNAVAILABLE",
                        "Serwer jest chwilowo przeciążony. Spróbuj ponownie za chwilę.", null,
                        correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }

    @ExceptionHandler(software.amazon.awssdk.services.s3.model.S3Exception.class)
    public ResponseEntity<ErrorResponse> storageFailure(software.amazon.awssdk.services.s3.model.S3Exception error,
                                                       jakarta.servlet.http.HttpServletRequest request) {
        if (error.statusCode() >= 500) return probeUnavailable(error);
        // Permission and request errors need operator attention, rather than retrying the upload.
        return new GlobalExceptionHandler().handleUnexpected(error, request);
    }

    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> quota(StorageQuotaExceededException error) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "STORAGE_QUOTA_EXCEEDED", error.getMessage(), null,
                correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }
}
