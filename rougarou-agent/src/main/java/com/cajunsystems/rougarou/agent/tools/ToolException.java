package com.cajunsystems.rougarou.agent.tools;

public class ToolException extends Exception {

    private final boolean retryable;

    public ToolException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ToolException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
