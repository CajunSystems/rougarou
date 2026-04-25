package com.cajunsystems.rougarou.core.events;

import java.time.Instant;
import java.util.Map;

public record SessionCreated(
        String sessionId,
        String agentId,
        String systemPrompt,
        Map<String, String> metadata,
        Instant timestamp
) implements RougarouEvent {
    public SessionCreated {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
