package com.cajunsystems.rougarou.examples;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;

import java.time.Duration;

/**
 * Demonstrates the gumbo-native scheduler.
 *
 * <p>We open a session, ask the scheduler to deliver a synthetic user turn 250 ms from now, and
 * wait for the assistant message to appear in the session log. No client is blocking on the
 * reply — the conversation is driven entirely by the scheduler.
 */
public final class ScheduledChatExample {

    public static void main(String[] args) throws Exception {
        try (var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build());
             RougarouClient client = RougarouClient.builder(sharedLog)
                     .runScheduleWorker()
                     .addAgentWorker(AgentWorkerConfig.builder()
                             .workerId("agent-1")
                             .llmClient(new EchoLlmClient())
                             .build())
                     .build()) {

            String sid = client.openSession(SessionConfig.builder().agentId("echo-agent").build());
            System.out.println("session: " + sid);

            String scheduleId = client.scheduler()
                    .scheduleUserInputAfter(sid, Duration.ofMillis(250), "scheduled hello")
                    .get();
            System.out.println("scheduled: " + scheduleId);

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                boolean done = client.memory().history(sid).get().stream()
                        .anyMatch(e -> e instanceof AssistantMessage);
                if (done) break;
                Thread.sleep(50);
            }

            System.out.println("conversation transcript:");
            client.memory().conversation(sid).get().forEach(t ->
                    System.out.println("  [" + t.role() + "] " + t.content()));
        }
    }
}
