package com.deepfake.orchestrator.exception;

public class TooManyStreamsException extends RuntimeException {
    public TooManyStreamsException() { super("Too many open analysis streams"); }
}
