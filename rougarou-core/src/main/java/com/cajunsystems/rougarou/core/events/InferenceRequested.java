package com.cajunsystems.rougarou.core.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Picked up by an agent worker. Carries the full conversation snapshot so the worker is
 * stateless and any worker can complete the request.
 */
public record InferenceRequested(
        String sessionId,
        String requestId,
        String agentId,
        List<Turn> conversation,
        List<String> availableTools,
        Map<String, String> metadata,
        Instant timestamp
) implements RougarouEvent {

    public InferenceRequested {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A single turn in the conversation given to the LLM. */
    public record Turn(String role, String content) {}
}
