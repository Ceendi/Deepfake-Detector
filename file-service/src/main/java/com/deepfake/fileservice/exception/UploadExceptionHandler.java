package com.deepfake.fileservice.exception;

import com.deepfake.fileservice.controller.FileUploadController;
import com.deepfake.fileservice.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.util.UUID;

@Order(-1)
@RestControllerAdvice(assignableTypes = FileUploadController.class)
public class UploadExceptionHandler {
    @ExceptionHandler({MediaProbeUnavailableException.class,
            SdkClientException.class,
            TransientDataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> probeUnavailable(RuntimeException error) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(new ErrorResponse("SERVICE_UNAVAILABLE",
                        "Serwer jest chwilowo przeciążony. Spróbuj ponownie za chwilę.", null,
                        correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }

    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<ErrorResponse> storageFailure(S3Exception error, HttpServletRequest request) {
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
