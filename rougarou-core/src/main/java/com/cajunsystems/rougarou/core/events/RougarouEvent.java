package com.cajunsystems.rougarou.core.events;

import java.time.Instant;

/**
 * Sealed root of every event written to the rougarou shared log.
 *
 * <p>The log is the single source of truth: gateway sessions, agent inference workers, and tool
 * workers all derive their state by reading these events and react by appending more of them.
 * Every event carries the originating session id so cross-cutting consumers can route by session
 * without unwrapping the variant.
 */
public sealed interface RougarouEvent permits
        SessionCreated,
        SessionClosed,
        UserMessage,
        AssistantMessage,
        InferenceRequested,
        InferenceCompleted,
        InferenceFailed,
        ToolRequested,
        ToolCompleted,
        ToolFailed,
        AgentError,
        ScheduleRequested,
        ScheduleCancelled,
        ScheduleFired {

    String sessionId();

    Instant timestamp();
}
