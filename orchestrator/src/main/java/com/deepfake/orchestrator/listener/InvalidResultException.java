package com.deepfake.orchestrator.listener;

/** Malformed detector data cannot improve on retry. */
public final class InvalidResultException extends IllegalArgumentException {
    public InvalidResultException(String message) {
        super(message);
    }
}
