package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/**
 * Request to deliver a {@code payload} to {@code sessionId} at {@code fireAt}.
 *
 * <p>Picked up by the scheduler worker subscribing to the {@code rougarou.schedule} tag.
 */
public record ScheduleRequested(
        String sessionId,
        String scheduleId,
        Instant fireAt,
        ScheduleKind kind,
        String payload,
        Instant timestamp
) implements RougarouEvent {}
