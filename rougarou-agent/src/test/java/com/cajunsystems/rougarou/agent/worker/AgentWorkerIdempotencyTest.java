package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.LlmClient;
import com.cajunsystems.rougarou.agent.llm.LlmRequest;
import com.cajunsystems.rougarou.agent.llm.LlmResponse;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AgentWorkerIdempotencyTest {

    @Test
    void skipsRequestThatAlreadyHasATerminalCompletionOnSessionTag() throws Exception {
        // Replicates the post-crash scenario: an earlier worker invocation produced an
        // InferenceCompleted but the worker died before persisting its checkpoint. On restart
        // (or for a peer worker), the InferenceRequested is delivered again. The worker MUST
        // detect the already-written InferenceCompleted on the session tag and skip the LLM
        // call — otherwise we'd double-charge the LLM and append a second AssistantMessage.
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-dupe-inf";
            String requestId = "req-dupe-1";

            // Pre-populate: SessionCreated, InferenceRequested, InferenceCompleted (terminal).
            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new InferenceRequested(sid, requestId, "agent",
                    List.of(new InferenceRequested.Turn("user", "hi")),
                    List.of(), Map.of(), Instant.now())).get();
            rl.append(new InferenceCompleted(sid, requestId, "the-original-reply",
                    List.of(), Instant.now())).get();

            // Counter LLM — would invoke once if not properly guarded.
            AtomicInteger llmCalls = new AtomicInteger();
            LlmClient countingLlm = req -> {
                llmCalls.incrementAndGet();
                return new LlmResponse("a different reply", List.of(), "end_turn");
            };

            try (AgentWorker worker = new AgentWorker(
                    AgentWorkerConfig.builder()
                            .workerId("worker-restart")
                            .llmClient(countingLlm)
                            .build(),
                    rl)) {
                worker.start();

                // The subscription replays InferenceRequested from BEGINNING. Without the
                // cross-tag check the worker would invoke the LLM and append a second
                // InferenceCompleted; with it, the worker skips and the log is unchanged.
                Thread.sleep(500);

                assertThat(llmCalls.get())
                        .as("LLM must not be re-invoked when a terminal completion already exists")
                        .isZero();

                long completedCount = rl.readSession(sid).join().stream()
                        .filter(e -> e instanceof InferenceCompleted).count();
                assertThat(completedCount)
                        .as("no duplicate InferenceCompleted should be appended")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void retriesARequestThatHasOnlyARetryableFailure() throws Exception {
        // Counter-case: a retryable InferenceFailed must NOT skip the LLM call — that's the
        // whole point of retryability. If the idempotency guard mistakenly tripped on
        // retryable failures the workflow would deadlock.
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-retry";
            String requestId = "req-retry-1";

            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new InferenceRequested(sid, requestId, "agent",
                    List.of(new InferenceRequested.Turn("user", "try again")),
                    List.of(), Map.of(), Instant.now())).get();
            rl.append(new com.cajunsystems.rougarou.core.events.InferenceFailed(
                    sid, requestId, "RateLimited", "429", 1, /* retryable= */ true,
                    Instant.now())).get();

            AtomicInteger llmCalls = new AtomicInteger();
            LlmClient countingLlm = req -> {
                llmCalls.incrementAndGet();
                return new LlmResponse("now it works", List.of(), "end_turn");
            };

            try (AgentWorker worker = new AgentWorker(
                    AgentWorkerConfig.builder()
                            .workerId("worker-retry")
                            .llmClient(countingLlm)
                            .build(),
                    rl)) {
                worker.start();

                await().atMost(3, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).join().stream()
                                .anyMatch(e -> e instanceof InferenceCompleted));

                assertThat(llmCalls.get())
                        .as("retryable failures should not block re-invocation")
                        .isEqualTo(1);
            }
        }
    }
}
