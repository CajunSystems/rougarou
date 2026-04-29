package com.cajunsystems.rougarou.gateway.session;

/**
 * Surfaced through {@code RougarouClient.send()} when an inference request exhausts its retry
 * budget without producing a response.
 */
public class InferenceTerminallyFailedException extends RuntimeException {

    private final String errorType;
    private final int attempt;

    public InferenceTerminallyFailedException(String errorType, String message, int attempt) {
        super(formatMessage(errorType, message, attempt));
        this.errorType = errorType;
        this.attempt = attempt;
    }

    public String errorType() { return errorType; }
    public int attempt() { return attempt; }

    private static String formatMessage(String errorType, String message, int attempt) {
        return "inference failed after " + attempt + " attempt(s): " + errorType + ": " + message;
    }
}
