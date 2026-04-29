package com.cajunsystems.rougarou.gateway.session;

/**
 * Surfaced through {@code RougarouClient.send()} when a tool invocation exhausts its retry
 * budget. The owning inference request can no longer make progress.
 */
public class ToolTerminallyFailedException extends RuntimeException {

    private final String toolName;
    private final String errorType;
    private final int attempt;

    public ToolTerminallyFailedException(String toolName, String errorType, String message, int attempt) {
        super(formatMessage(toolName, errorType, message, attempt));
        this.toolName = toolName;
        this.errorType = errorType;
        this.attempt = attempt;
    }

    public String toolName() { return toolName; }
    public String errorType() { return errorType; }
    public int attempt() { return attempt; }

    private static String formatMessage(String toolName, String errorType, String message, int attempt) {
        return "tool '" + toolName + "' failed after " + attempt + " attempt(s): " + errorType + ": " + message;
    }
}
