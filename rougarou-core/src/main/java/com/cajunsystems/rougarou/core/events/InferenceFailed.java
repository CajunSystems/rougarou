package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record InferenceFailed(
        String sessionId,
        String requestId,
        String errorType,
        String message,
        int attempt,
        boolean retryable,
        Instant timestamp
) implements RougarouEvent {}
