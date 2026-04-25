package com.cajunsystems.rougarou.agent.llm;

import java.util.List;

/**
 * A deterministic in-process LLM stub for tests and demos.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If the conversation's last user message starts with {@code "tool:"}, the response asks the
 *       harness to call the named tool, e.g. {@code "tool:echo {\"text\":\"hi\"}"}.</li>
 *   <li>Otherwise, the response is a static reply that quotes the last user message.</li>
 * </ul>
 */
public final class EchoLlmClient implements LlmClient {

    private static final String TOOL_PREFIX = "tool:";

    @Override
    public LlmResponse complete(LlmRequest request) {
        // If the very last turn is a tool result, finalize the conversation with a summary —
        // otherwise we'd re-issue the same tool call and loop forever.
        if (!request.conversation().isEmpty()) {
            LlmRequest.Turn last = request.conversation().get(request.conversation().size() - 1);
            if ("tool".equalsIgnoreCase(last.role())) {
                return new LlmResponse("Tool completed: " + last.content(), List.of(), "end_turn");
            }
        }

        String lastUser = request.conversation().reversed().stream()
                .filter(t -> "user".equalsIgnoreCase(t.role()))
                .map(LlmRequest.Turn::content)
                .findFirst()
                .orElse("");

        if (lastUser.startsWith(TOOL_PREFIX)) {
            String rest = lastUser.substring(TOOL_PREFIX.length()).trim();
            int sp = rest.indexOf(' ');
            String toolName = sp < 0 ? rest : rest.substring(0, sp);
            String args = sp < 0 ? "{}" : rest.substring(sp + 1).trim();
            return new LlmResponse(
                    "",
                    List.of(new LlmResponse.ToolCall("call-" + System.nanoTime(), toolName, args)),
                    "tool_use"
            );
        }

        return new LlmResponse("Echo: " + lastUser, List.of(), "end_turn");
    }
}
