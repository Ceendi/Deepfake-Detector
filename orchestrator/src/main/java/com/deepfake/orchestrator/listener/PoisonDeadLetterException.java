package com.deepfake.orchestrator.listener;

/** Invalid terminal messages are quarantined once; database failures pause and requeue without a limit. */
public final class PoisonDeadLetterException extends RuntimeException {
    public PoisonDeadLetterException(String message, Throwable cause) { super(message, cause); }
}
