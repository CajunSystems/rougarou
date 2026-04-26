package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/**
 * Emitted by the scheduler when a {@link ScheduleRequested} reaches its {@code fireAt}.
 *
 * <p>This event also lands on the schedule tag so a restarted scheduler knows the entry has
 * already been processed and skips re-firing it.
 */
public record ScheduleFired(
        String sessionId,
        String scheduleId,
        ScheduleKind kind,
        String payload,
        Instant firedAt,
        Instant timestamp
) implements RougarouEvent {}
