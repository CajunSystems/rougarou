package com.cajunsystems.rougarou.agent.llm;

/** Wraps any provider-specific error so the agent worker can decide on retry policy. */
public class LlmException extends Exception {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
