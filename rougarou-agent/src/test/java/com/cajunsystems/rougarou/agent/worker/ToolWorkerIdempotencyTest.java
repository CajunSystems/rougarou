package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.agent.tools.ToolException;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolCompleted;
import com.cajunsystems.rougarou.core.events.ToolFailed;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ToolWorkerIdempotencyTest {

    /** Counts every invocation; used to assert "the tool ran exactly N times." */
    private static final class CountingTool implements Tool {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "echo"; }
        @Override public String description() { return "test tool"; }
        @Override public String inputSchemaJson() { return "{}"; }
        @Override public String invoke(String argsJson) {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        }
    }

    @Test
    void skipsRequestThatAlreadyHasATerminalCompletionOnSessionTag() throws Exception {
        // Replicates the post-checkpoint-failure scenario: a previous worker invocation produced
        // a ToolCompleted, then the worker died before persisting its checkpoint. On restart the
        // ToolRequested is delivered again and MUST NOT cause the tool's side effect to run a
        // second time.
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-tool-dupe";
            String toolCallId = "tc-dupe-1";

            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new ToolRequested(sid, "req-1", toolCallId, "echo", "{}", Instant.now())).get();
            rl.append(new ToolCompleted(sid, "req-1", toolCallId, "echo",
                    "{\"original\":true}", Instant.now())).get();

            CountingTool tool = new CountingTool();
            ToolRegistry registry = new ToolRegistry().register(tool);

            try (ToolWorker worker = new ToolWorker(
                    ToolWorkerConfig.builder()
                            .workerId("worker-restart")
                            .toolRegistry(registry)
                            .build(),
                    rl)) {
                worker.start();

                Thread.sleep(500);

                assertThat(tool.calls.get())
                        .as("tool must NOT be re-invoked when a terminal completion already exists")
                        .isZero();

                long completedCount = rl.readSession(sid).join().stream()
                        .filter(e -> e instanceof ToolCompleted).count();
                assertThat(completedCount)
                        .as("no duplicate ToolCompleted should be appended")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void retriesARequestThatHasOnlyARetryableFailure() throws Exception {
        // Counter-test: a retryable ToolFailed must NOT trigger the skip — that's the whole
        // point of retryability.
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-tool-retry";
            String toolCallId = "tc-retry-1";

            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new ToolRequested(sid, "req-1", toolCallId, "echo", "{}", Instant.now())).get();
            rl.append(new ToolFailed(sid, "req-1", toolCallId, "echo",
                    "TransientError", "first try fizzled",
                    1, /* retryable= */ true, Instant.now())).get();

            CountingTool tool = new CountingTool();
            ToolRegistry registry = new ToolRegistry().register(tool);

            try (ToolWorker worker = new ToolWorker(
                    ToolWorkerConfig.builder()
                            .workerId("worker-retry")
                            .toolRegistry(registry)
                            .build(),
                    rl)) {
                worker.start();

                await().atMost(3, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).join().stream().anyMatch(e -> e instanceof ToolCompleted));

                assertThat(tool.calls.get())
                        .as("retryable failures should not block re-invocation")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void skipsAfterANonRetryableFailure() throws Exception {
        // A non-retryable ToolFailed is also terminal — the skip applies.
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-tool-fatal";
            String toolCallId = "tc-fatal-1";

            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new ToolRequested(sid, "req-1", toolCallId, "echo", "{}", Instant.now())).get();
            rl.append(new ToolFailed(sid, "req-1", toolCallId, "echo",
                    "BadInput", "won't get better with retries",
                    3, /* retryable= */ false, Instant.now())).get();

            CountingTool tool = new CountingTool();
            ToolRegistry registry = new ToolRegistry().register(tool);

            try (ToolWorker worker = new ToolWorker(
                    ToolWorkerConfig.builder()
                            .workerId("worker-fatal")
                            .toolRegistry(registry)
                            .build(),
                    rl)) {
                worker.start();
                Thread.sleep(500);

                assertThat(tool.calls.get())
                        .as("tool must NOT be re-invoked after a non-retryable failure")
                        .isZero();
            }
        }
    }
}
