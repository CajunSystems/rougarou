package com.cajunsystems.rougarou.core.events;

import java.time.Instant;
import java.util.List;

/**
 * The agent worker's response to an {@link InferenceRequested}. Either contains the assistant's
 * final text (terminating turn) or a list of tool calls that the gateway must dispatch and feed
 * back before requesting more inference.
 */
public record InferenceCompleted(
        String sessionId,
        String requestId,
        String content,
        List<ToolCall> toolCalls,
        Instant timestamp
) implements RougarouEvent {

    public InferenceCompleted {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** A request from the LLM to invoke a named tool with structured arguments. */
    public record ToolCall(String toolCallId, String toolName, String argsJson) {}
}
