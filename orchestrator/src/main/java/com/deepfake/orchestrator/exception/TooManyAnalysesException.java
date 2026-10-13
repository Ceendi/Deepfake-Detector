package com.deepfake.orchestrator.exception;

/** Raised by BackpressureGuard when the in-flight analysis limit is hit — mapped to 429. */
public class TooManyAnalysesException extends RuntimeException {

    public enum Reason { GLOBAL_LIMIT, USER_LIMIT }

    private final Reason reason;
    private final int limit;
    private final int queuePosition;
    private final int retryAfterSeconds;

    public TooManyAnalysesException(Reason reason, int limit, int queuePosition, int retryAfterSeconds) {
        super("In-flight analysis limit exceeded");
        this.reason = reason;
        this.limit = limit;
        this.queuePosition = queuePosition;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason reason() {
        return reason;
    }

    public int limit() {
        return limit;
    }

    public int queuePosition() {
        return queuePosition;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
