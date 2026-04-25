package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/** A finalized assistant message ready for display to the user. */
public record AssistantMessage(
        String sessionId,
        String messageId,
        String content,
        Instant timestamp
) implements RougarouEvent {}
