package com.cajunsystems.rougarou.gateway.memory;

import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.UserMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only projection of session history derived from the rougarou shared log.
 *
 * <p>Because the log is the source of truth, memory views are inherently consistent across all
 * gateway and agent processes — there's no separate cache to keep in sync.
 */
public final class AgentMemory {

    private final RougarouLog log;

    public AgentMemory(RougarouLog log) {
        this.log = log;
    }

    /** Full event history for one session, in append order. */
    public CompletableFuture<List<RougarouEvent>> history(String sessionId) {
        return log.readSession(sessionId);
    }

    /** Just the user/assistant turns (skipping inference and tool plumbing). */
    public CompletableFuture<List<Turn>> conversation(String sessionId) {
        return history(sessionId).thenApply(events -> events.stream()
                .map(AgentMemory::toTurn)
                .filter(t -> t != null)
                .toList());
    }

    private static Turn toTurn(RougarouEvent event) {
        if (event instanceof UserMessage u) return new Turn("user", u.content(), u.timestamp().toEpochMilli());
        if (event instanceof AssistantMessage a) return new Turn("assistant", a.content(), a.timestamp().toEpochMilli());
        return null;
    }

    public record Turn(String role, String content, long timestampMillis) {}
}
