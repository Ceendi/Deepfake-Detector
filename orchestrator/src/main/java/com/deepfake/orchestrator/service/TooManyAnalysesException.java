package com.deepfake.orchestrator.service;

/** Raised by {@link BackpressureGuard} when the in-flight analysis limit is hit — mapped to 429. */
public class TooManyAnalysesException extends RuntimeException {

    private final int queuePosition;
    private final int retryAfterSeconds;

    public TooManyAnalysesException(int queuePosition, int retryAfterSeconds) {
        super("In-flight analysis limit exceeded");
        this.queuePosition = queuePosition;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int queuePosition() {
        return queuePosition;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
