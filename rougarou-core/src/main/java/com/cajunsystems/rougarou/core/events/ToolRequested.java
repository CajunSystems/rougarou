package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/** Picked up by a tool worker. Stateless dispatch — args travel with the event. */
public record ToolRequested(
        String sessionId,
        String requestId,
        String toolCallId,
        String toolName,
        String argsJson,
        Instant timestamp
) implements RougarouEvent {}
