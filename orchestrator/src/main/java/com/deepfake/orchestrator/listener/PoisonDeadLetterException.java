package com.deepfake.orchestrator.listener;

/** Invalid terminal messages are quarantined once; database failures receive bounded retries. */
public final class PoisonDeadLetterException extends RuntimeException {
    public PoisonDeadLetterException(String message, Throwable cause) { super(message, cause); }
}
