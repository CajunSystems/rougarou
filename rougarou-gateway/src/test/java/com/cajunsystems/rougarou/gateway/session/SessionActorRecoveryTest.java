package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.core.Ids;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceFailed;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SessionActorRecoveryTest {

    @Test
    void preStartReEmitsInferenceForOrphanedUserMessage() throws Exception {
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-recovery";

            // Simulate the crash: a SessionCreated and a UserMessage got persisted, but the
            // matching InferenceRequested was never written (the actor died before its mailbox
            // delivered the EventArrived).
            rl.append(new SessionCreated(sid, "agent", "you are a helpful assistant",
                    Map.of(), Instant.now())).get();
            rl.append(new UserMessage(sid, Ids.newMessageId(), "are you there?", Instant.now())).get();

            // Spawn a fresh SessionActor over the existing log — preStart should detect the
            // orphan and emit an InferenceRequested itself.
            try (BayouSystem bayou = new BayouSystem(sharedLog)) {
                SessionConfig config = SessionConfig.builder().agentId("agent").build();
                bayou.spawn("rougarou-session-" + sid, new SessionActor(sid, config, rl));

                await().atMost(5, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).get().stream().anyMatch(e -> e instanceof InferenceRequested));

                long inferenceCount = rl.readSession(sid).get().stream()
                        .filter(e -> e instanceof InferenceRequested).count();
                assertThat(inferenceCount).isEqualTo(1);
            }
        }
    }

    @Test
    void preStartReSchedulesInferenceWithSameRequestIdWhenMidFlight() throws Exception {
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-mid";
            String requestId = "req-orig-1";

            // Simulate: SessionCreated → UserMessage → InferenceRequested, but no completion.
            rl.append(new SessionCreated(sid, "agent", "", Map.of(), Instant.now())).get();
            rl.append(new UserMessage(sid, Ids.newMessageId(), "hi", Instant.now())).get();
            rl.append(new InferenceRequested(sid, requestId, "agent",
                    java.util.List.of(new InferenceRequested.Turn("user", "hi")),
                    java.util.List.of(), Map.of(), Instant.now())).get();

            // On respawn, the actor should re-emit an InferenceRequested with the SAME requestId
            // so any worker that resumes is idempotent.
            try (BayouSystem bayou = new BayouSystem(sharedLog)) {
                SessionConfig config = SessionConfig.builder().agentId("agent").build();
                bayou.spawn("rougarou-session-" + sid, new SessionActor(sid, config, rl));

                await().atMost(5, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).get().stream()
                                .filter(e -> e instanceof InferenceRequested)
                                .count() == 2);

                long sameId = rl.readSession(sid).get().stream()
                        .filter(e -> e instanceof InferenceRequested ir && ir.requestId().equals(requestId))
                        .count();
                assertThat(sameId).isEqualTo(2);
            }
        }
    }

    @Test
    void permanentInferenceFailureCompletesWaiterExceptionally() throws Exception {
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-fail";

            try (BayouSystem bayou = new BayouSystem(sharedLog)) {
                SessionConfig config = SessionConfig.builder().agentId("agent").build();
                var ref = bayou.spawn("rougarou-session-" + sid,
                        new SessionActor(sid, config, rl));

                CompletableFuture<String> reply = new CompletableFuture<>();
                ref.tell(new SessionMessage.UserInput("crash please", reply));

                // Wait for the InferenceRequested to land, then synthesize a non-retryable failure.
                await().atMost(2, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).get().stream().anyMatch(e -> e instanceof InferenceRequested));

                String requestId = rl.readSession(sid).get().stream()
                        .filter(e -> e instanceof InferenceRequested)
                        .map(e -> ((InferenceRequested) e).requestId())
                        .findFirst().orElseThrow();

                rl.append(new InferenceFailed(
                        sid, requestId, "OverloadedError", "model is on fire",
                        3, /* retryable= */ false, Instant.now())).get();

                assertThatThrownBy(() -> reply.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(InferenceTerminallyFailedException.class)
                        .hasMessageContaining("OverloadedError")
                        .hasMessageContaining("model is on fire");
            }
        }
    }

    @Test
    void retryableInferenceFailureLeavesWaiterParked() throws Exception {
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(sharedLog);
            String sid = "ses-retry";

            try (BayouSystem bayou = new BayouSystem(sharedLog)) {
                SessionConfig config = SessionConfig.builder().agentId("agent").build();
                var ref = bayou.spawn("rougarou-session-" + sid,
                        new SessionActor(sid, config, rl));

                CompletableFuture<String> reply = new CompletableFuture<>();
                ref.tell(new SessionMessage.UserInput("hi", reply));

                await().atMost(2, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).get().stream().anyMatch(e -> e instanceof InferenceRequested));

                String requestId = rl.readSession(sid).get().stream()
                        .filter(e -> e instanceof InferenceRequested)
                        .map(e -> ((InferenceRequested) e).requestId())
                        .findFirst().orElseThrow();

                // Retryable failure — waiter should still be parked.
                rl.append(new InferenceFailed(
                        sid, requestId, "RateLimited", "429", 1, /* retryable= */ true, Instant.now())).get();

                Thread.sleep(200);
                assertThat(reply).isNotDone();

                // Then a real success comes through — waiter should complete.
                rl.append(new InferenceCompleted(
                        sid, requestId, "ok thanks", java.util.List.of(), Instant.now())).get();

                String got = reply.get(2, TimeUnit.SECONDS);
                assertThat(got).isEqualTo("ok thanks");
            }
        }
    }
}
