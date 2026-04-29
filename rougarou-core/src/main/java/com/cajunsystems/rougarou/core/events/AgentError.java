package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/** A terminal error that bubbled up past the harness's retry budget. */
public record AgentError(
        String sessionId,
        String errorType,
        String message,
        Instant timestamp
) implements RougarouEvent {}
