package com.deepfake.fileservice.validation;

/** Rejected upload (wrong magic bytes / not a real media container) -> 422 in GlobalExceptionHandler. */
public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) {
        super(message);
    }
}
