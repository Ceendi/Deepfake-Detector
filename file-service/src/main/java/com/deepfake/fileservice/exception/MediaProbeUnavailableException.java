package com.deepfake.fileservice.exception;

public class MediaProbeUnavailableException extends RuntimeException {
    public MediaProbeUnavailableException(Throwable cause) {
        super("Media probe temporarily unavailable", cause);
    }
}
