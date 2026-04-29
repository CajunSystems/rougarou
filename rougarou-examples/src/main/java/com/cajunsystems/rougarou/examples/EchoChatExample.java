package com.cajunsystems.rougarou.examples;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.agent.worker.ToolWorkerConfig;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;

import java.util.List;

/**
 * End-to-end smoke demo. Spins up a single-JVM rougarou with the deterministic {@link EchoLlmClient}
 * and an echo tool, then runs a couple of conversation turns through it — including a turn that
 * exercises tool dispatch.
 */
public final class EchoChatExample {

    public static void main(String[] args) throws Exception {
        var sharedLogConfig = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();
        try (var sharedLog = SharedLogService.open(sharedLogConfig)) {

            // Skills + tools — usable by both the agent worker (for tool specs) and the tool worker
            // (for actual invocation).
            SkillRegistry skills = new SkillRegistry().register(new EchoSkill());
            ToolRegistry tools = skills.materializeTools(List.of("echo"));

            try (RougarouClient client = RougarouClient.builderOwning(sharedLog)
                    .addAgentWorker(AgentWorkerConfig.builder()
                            .workerId("agent-1")
                            .llmClient(new EchoLlmClient())
                            .toolRegistry(tools)
                            .build())
                    .addToolWorker(ToolWorkerConfig.builder()
                            .workerId("tool-1")
                            .toolRegistry(tools)
                            .build())
                    .build()) {

                String sessionId = client.openSession(SessionConfig.builder()
                        .agentId("echo-agent")
                        .systemPrompt("You are a friendly echo agent.")
                        .activeSkills(List.of("echo"))
                        .skillRegistry(skills)
                        .build());

                System.out.println("session: " + sessionId);

                String r1 = client.send(sessionId, "Hello there!").get();
                System.out.println("user → Hello there!");
                System.out.println("assistant → " + r1);

                String r2 = client.send(sessionId, "tool:echo {\"text\":\"loop test\"}").get();
                System.out.println("user → tool:echo {\"text\":\"loop test\"}");
                System.out.println("assistant → " + r2);

                System.out.println();
                System.out.println("conversation transcript:");
                client.memory().conversation(sessionId).get().forEach(t ->
                        System.out.println("  [" + t.role() + "] " + t.content()));

                client.close(sessionId, "demo-done").get();
            }
        }
    }
}
