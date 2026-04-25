package com.cajunsystems.rougarou.core;

import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import com.cajunsystems.rougarou.core.events.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RougarouEventSerializerTest {

    private final RougarouEventSerializer serializer = new RougarouEventSerializer();

    @Test
    void roundTrips_sessionCreated() {
        SessionCreated original = new SessionCreated(
                "ses-1", "agent-x", "be helpful",
                Map.of("k", "v"), Instant.parse("2026-01-01T00:00:00Z"));
        byte[] bytes = serializer.serialize(original);
        RougarouEvent decoded = serializer.deserialize(bytes);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTrips_userMessage() {
        UserMessage original = new UserMessage("ses-1", "msg-1", "hello", Instant.now());
        byte[] bytes = serializer.serialize(original);
        assertThat(serializer.deserialize(bytes)).isEqualTo(original);
    }

    @Test
    void roundTrips_assistantMessage() {
        AssistantMessage original = new AssistantMessage("ses-1", "msg-2", "hi back", Instant.now());
        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    void roundTrips_inferenceRequested_withNestedTurns() {
        InferenceRequested original = new InferenceRequested(
                "ses-1", "req-1", "agent-x",
                List.of(new InferenceRequested.Turn("user", "hi"),
                        new InferenceRequested.Turn("assistant", "hello")),
                List.of("echo", "search"),
                Map.of(),
                Instant.now()
        );
        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    void roundTrips_inferenceCompleted_withToolCalls() {
        InferenceCompleted original = new InferenceCompleted(
                "ses-1", "req-1", "",
                List.of(new InferenceCompleted.ToolCall("tc-1", "echo", "{\"text\":\"hi\"}")),
                Instant.now()
        );
        InferenceCompleted decoded = (InferenceCompleted) serializer.deserialize(serializer.serialize(original));
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.hasToolCalls()).isTrue();
    }

    @Test
    void roundTrips_toolRequested() {
        ToolRequested original = new ToolRequested("ses-1", "req-1", "tc-1", "echo", "{}", Instant.now());
        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }
}
