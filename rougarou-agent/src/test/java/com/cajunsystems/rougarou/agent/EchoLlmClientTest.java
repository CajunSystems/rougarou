package com.cajunsystems.rougarou.agent;

import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.llm.LlmException;
import com.cajunsystems.rougarou.agent.llm.LlmRequest;
import com.cajunsystems.rougarou.agent.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EchoLlmClientTest {

    private final EchoLlmClient client = new EchoLlmClient();

    @Test
    void echoesLastUserMessage() throws LlmException {
        LlmResponse response = client.complete(new LlmRequest(
                "echo",
                List.of(new LlmRequest.Turn("user", "hello world")),
                List.of(),
                Map.of()
        ));
        assertThat(response.content()).isEqualTo("Echo: hello world");
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void issuesToolCallWhenUserPrefixed() throws LlmException {
        LlmResponse response = client.complete(new LlmRequest(
                "echo",
                List.of(new LlmRequest.Turn("user", "tool:echo {\"text\":\"x\"}")),
                List.of(),
                Map.of()
        ));
        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls().get(0).name()).isEqualTo("echo");
        assertThat(response.toolCalls().get(0).argsJson()).isEqualTo("{\"text\":\"x\"}");
    }

    @Test
    void summarizesAfterToolResult() throws LlmException {
        LlmResponse response = client.complete(new LlmRequest(
                "echo",
                List.of(
                        new LlmRequest.Turn("user", "tool:echo {\"text\":\"x\"}"),
                        new LlmRequest.Turn("assistant", "[tool_call:echo ...]"),
                        new LlmRequest.Turn("tool", "[tool_result: ...]")
                ),
                List.of(),
                Map.of()
        ));
        assertThat(response.content()).startsWith("Tool completed:");
        assertThat(response.hasToolCalls()).isFalse();
    }
}
