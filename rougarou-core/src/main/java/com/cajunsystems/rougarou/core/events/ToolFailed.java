package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record ToolFailed(
        String sessionId,
        String requestId,
        String toolCallId,
        String toolName,
        String errorType,
        String message,
        int attempt,
        boolean retryable,
        Instant timestamp
) implements RougarouEvent {}
