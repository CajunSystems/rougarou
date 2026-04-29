package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record ToolCompleted(
        String sessionId,
        String requestId,
        String toolCallId,
        String toolName,
        String resultJson,
        Instant timestamp
) implements RougarouEvent {}
