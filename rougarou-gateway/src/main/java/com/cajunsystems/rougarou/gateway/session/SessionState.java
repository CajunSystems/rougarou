package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceFailed;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionClosed;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolCompleted;
import com.cajunsystems.rougarou.core.events.ToolFailed;
import com.cajunsystems.rougarou.core.events.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable in-memory projection of a session, derived by folding {@link RougarouEvent events}
 * read from the session's log tag.
 *
 * <p>Used by {@link SessionActor} to drive scheduling decisions and to materialize the conversation
 * snapshot fed to {@link InferenceRequested}.
 */
public final class SessionState {

    public enum Status { OPEN, CLOSED }

    private String agentId = "";
    private String systemPrompt = "";
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final List<Turn> conversation = new ArrayList<>();
    private final Map<String, PendingToolCall> pendingToolCalls = new LinkedHashMap<>();
    /** Ids of UserMessages already folded into {@link #conversation}, used to make {@link #apply}
     *  idempotent. The scheduler emits a deterministic id derived from {@code scheduleId} so a
     *  multi-process double-fire produces two identical UserMessage events; the second is dropped. */
    private final Set<String> appliedUserMessageIds = new HashSet<>();
    private String pendingInferenceRequestId;
    private Status status = Status.OPEN;
    private String lastAssistantMessage = "";

    /** A single conversation turn used both for log replay and LLM input. */
    public record Turn(String role, String content) {}

    public record PendingToolCall(String requestId, String toolCallId, String toolName, String argsJson) {}

    public String agentId() { return agentId; }
    public String systemPrompt() { return systemPrompt; }
    public Map<String, String> metadata() { return Map.copyOf(metadata); }
    public List<Turn> conversation() { return List.copyOf(conversation); }
    public Status status() { return status; }
    public String pendingInferenceRequestId() { return pendingInferenceRequestId; }
    public Map<String, PendingToolCall> pendingToolCalls() { return Map.copyOf(pendingToolCalls); }
    public boolean hasPendingWork() { return pendingInferenceRequestId != null || !pendingToolCalls.isEmpty(); }
    public String lastAssistantMessage() { return lastAssistantMessage; }

    /** True if a UserMessage with this id has already been folded into the conversation. */
    public boolean hasAppliedUserMessage(String messageId) {
        return appliedUserMessageIds.contains(messageId);
    }

    public void apply(RougarouEvent event) {
        switch (event) {
            case SessionCreated c -> {
                this.agentId = c.agentId();
                this.systemPrompt = c.systemPrompt() == null ? "" : c.systemPrompt();
                this.metadata.putAll(c.metadata());
                if (!systemPrompt.isEmpty()) {
                    conversation.add(new Turn("system", systemPrompt));
                }
            }
            case UserMessage u -> {
                // Idempotent fold: if a duplicate UserMessage arrives (e.g. from racing scheduler
                // processes that both fired the same scheduleId), skip the second one so the
                // conversation snapshot doesn't double-count the turn.
                if (appliedUserMessageIds.add(u.messageId())) {
                    conversation.add(new Turn("user", u.content()));
                }
            }
            case AssistantMessage a -> {
                conversation.add(new Turn("assistant", a.content()));
                this.lastAssistantMessage = a.content();
            }
            case InferenceRequested r -> this.pendingInferenceRequestId = r.requestId();
            case InferenceCompleted c -> {
                if (c.requestId().equals(pendingInferenceRequestId)) {
                    this.pendingInferenceRequestId = null;
                }
                if (!c.hasToolCalls() && c.content() != null && !c.content().isEmpty()) {
                    // an assistant text response was produced — but we wait for AssistantMessage event
                    // (the gateway emits that explicitly so callers can distinguish raw inference output
                    // from the gateway-finalized message)
                }
                if (c.hasToolCalls()) {
                    for (var tc : c.toolCalls()) {
                        pendingToolCalls.put(tc.toolCallId(),
                                new PendingToolCall(c.requestId(), tc.toolCallId(), tc.toolName(), tc.argsJson()));
                        conversation.add(new Turn("assistant",
                                "[tool_call:" + tc.toolName() + " id=" + tc.toolCallId() + " args=" + tc.argsJson() + "]"));
                    }
                }
            }
            case ToolCompleted tc -> {
                pendingToolCalls.remove(tc.toolCallId());
                conversation.add(new Turn("tool",
                        "[tool_result:" + tc.toolName() + " id=" + tc.toolCallId() + " " + tc.resultJson() + "]"));
            }
            case ToolFailed tf -> {
                if (!tf.retryable()) {
                    pendingToolCalls.remove(tf.toolCallId());
                    conversation.add(new Turn("tool",
                            "[tool_error:" + tf.toolName() + " id=" + tf.toolCallId() + " " + tf.message() + "]"));
                }
            }
            case InferenceFailed f -> {
                // A terminal failure must clear the pending request id, otherwise crash recovery
                // will see the still-pending id and re-emit InferenceRequested forever.
                if (!f.retryable() && f.requestId().equals(pendingInferenceRequestId)) {
                    this.pendingInferenceRequestId = null;
                }
            }
            case SessionClosed ignored -> this.status = Status.CLOSED;
            default -> { /* AgentError is observed but doesn't change conversation */ }
        }
    }
}
