package com.cajunsystems.rougarou.agent.llm;

import java.util.List;

/** A provider-agnostic response from an LLM. Either a final text or a list of tool calls. */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        String stopReason
) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }

    public record ToolCall(String id, String name, String argsJson) {}
}
