package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record UserMessage(
        String sessionId,
        String messageId,
        String content,
        Instant timestamp
) implements RougarouEvent {}
