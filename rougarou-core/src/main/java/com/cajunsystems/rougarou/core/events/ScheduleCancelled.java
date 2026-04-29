package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

public record ScheduleCancelled(
        String sessionId,
        String scheduleId,
        String reason,
        Instant timestamp
) implements RougarouEvent {}
