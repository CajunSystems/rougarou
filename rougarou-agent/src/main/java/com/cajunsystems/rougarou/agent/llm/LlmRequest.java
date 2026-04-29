package com.cajunsystems.rougarou.agent.llm;

import java.util.List;
import java.util.Map;

/** A provider-agnostic request to an LLM. */
public record LlmRequest(
        String model,
        List<Turn> conversation,
        List<ToolSpec> availableTools,
        Map<String, String> metadata
) {

    public LlmRequest {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record Turn(String role, String content) {}

    public record ToolSpec(String name, String description, String inputSchemaJson) {}
}
