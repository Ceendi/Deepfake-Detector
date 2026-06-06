package com.deepfake.fileservice.exception;

/** Rejected upload (wrong magic bytes / not a real media container) -> 422 in GlobalExceptionHandler. */
public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) {
        super(message);
    }
}
