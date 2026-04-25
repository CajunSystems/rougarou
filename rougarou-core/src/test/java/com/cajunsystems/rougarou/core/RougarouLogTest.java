package com.cajunsystems.rougarou.core;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import com.cajunsystems.rougarou.core.events.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RougarouLogTest {

    @Test
    void appendsAndReadsBackEventsInOrder() throws Exception {
        try (SharedLogService log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build())) {

            RougarouLog rl = new RougarouLog(log);
            String sid = "ses-test";

            rl.append(new SessionCreated(sid, "agent", "system prompt", Map.of(), Instant.now())).get();
            rl.append(new UserMessage(sid, "msg-1", "hi", Instant.now())).get();

            List<RougarouEvent> events = rl.readSession(sid).get();

            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(SessionCreated.class);
            assertThat(events.get(1)).isInstanceOf(UserMessage.class);
        }
    }

    @Test
    void inferenceRequestedIsTaggedToInferenceQueue() throws Exception {
        try (SharedLogService log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build())) {

            RougarouLog rl = new RougarouLog(log);
            ConcurrentLinkedQueue<RougarouEvent> received = new ConcurrentLinkedQueue<>();
            var sub = log.subscribe(RougarouTags.inferenceTasks(),
                    com.cajunsystems.gumbo.core.LogPosition.BEGINNING,
                    e -> received.add(rl.decode(e)));

            rl.append(new InferenceRequested(
                    "ses-1", "req-1", "agent",
                    List.of(new InferenceRequested.Turn("user", "hi")),
                    List.of(),
                    Map.of(),
                    Instant.now())).get();

            await().atMost(2, TimeUnit.SECONDS).until(() -> received.size() == 1);
            assertThat(received.peek()).isInstanceOf(InferenceRequested.class);
            sub.close();
        }
    }

    @Test
    void toolRequestedIsTaggedToToolQueue() throws Exception {
        try (SharedLogService log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build())) {

            RougarouLog rl = new RougarouLog(log);
            ConcurrentLinkedQueue<RougarouEvent> received = new ConcurrentLinkedQueue<>();
            var sub = log.subscribe(RougarouTags.toolTasks(),
                    com.cajunsystems.gumbo.core.LogPosition.BEGINNING,
                    e -> received.add(rl.decode(e)));

            rl.append(new ToolRequested("ses-1", "req-1", "tc-1", "echo", "{}", Instant.now())).get();

            await().atMost(2, TimeUnit.SECONDS).until(() -> received.size() == 1);
            assertThat(received.peek()).isInstanceOf(ToolRequested.class);
            sub.close();
        }
    }
}
