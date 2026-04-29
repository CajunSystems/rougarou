package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record SessionClosed(
        String sessionId,
        String reason,
        Instant timestamp
) implements RougarouEvent {}
