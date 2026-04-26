package com.cajunsystems.rougarou.gateway.scheduler;

import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.ScheduleCancelled;
import com.cajunsystems.rougarou.core.events.ScheduleKind;
import com.cajunsystems.rougarou.core.events.ScheduleRequested;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for requesting future deliveries to a session.
 *
 * <p>The scheduler is purely a writer of events to the {@code rougarou.schedule} tag — it doesn't
 * own the timing thread. That work happens in {@link ScheduleWorker}, which a deployment can run
 * in any number of processes (the schedule tag is the source of truth).
 */
public final class Scheduler {

    private final RougarouLog log;

    public Scheduler(RougarouLog log) {
        this.log = log;
    }

    /** Schedule a synthetic user input to land in {@code sessionId} at {@code fireAt}. */
    public CompletableFuture<String> scheduleUserInputAt(String sessionId, Instant fireAt, String content) {
        String scheduleId = "sch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return log.append(new ScheduleRequested(
                sessionId,
                scheduleId,
                fireAt,
                ScheduleKind.USER_INPUT,
                content,
                Instant.now()
        )).thenApply(r -> scheduleId);
    }

    /** Schedule a synthetic user input to land after {@code delay} from now. */
    public CompletableFuture<String> scheduleUserInputAfter(String sessionId, Duration delay, String content) {
        return scheduleUserInputAt(sessionId, Instant.now().plus(delay), content);
    }

    /**
     * Cancel a previously scheduled fire. If the fire has already happened (or never existed),
     * this is a no-op as far as delivery goes — the event is still appended for audit.
     */
    public CompletableFuture<Void> cancel(String sessionId, String scheduleId, String reason) {
        return log.append(new ScheduleCancelled(sessionId, scheduleId, reason, Instant.now()))
                .thenApply(r -> null);
    }
}
