package com.cajunsystems.rougarou.client;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.skills.Skill;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.EchoTool;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.agent.worker.ToolWorkerConfig;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RougarouClientIntegrationTest {

    @Test
    void roundTripsAUserMessageThroughInference() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            ToolRegistry tools = new ToolRegistry();
            try (RougarouClient client = RougarouClient.builder(log)
                    .addAgentWorker(AgentWorkerConfig.builder()
                            .workerId("a")
                            .llmClient(new EchoLlmClient())
                            .toolRegistry(tools)
                            .build())
                    .build()) {

                String sid = client.openSession(SessionConfig.builder()
                        .agentId("a")
                        .systemPrompt("hi")
                        .build());

                String reply = client.send(sid, "hello").get(10, TimeUnit.SECONDS);

                assertThat(reply).isEqualTo("Echo: hello");
            }
        }
    }

    @Test
    void roundTripsAToolCallAndFollowUp() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            SkillRegistry skills = new SkillRegistry().register(new Skill() {
                @Override public String name() { return "echo"; }
                @Override public String description() { return "echo skill"; }
                @Override public String promptFragment() { return ""; }
                @Override public List<Tool> tools() { return List.of(new EchoTool()); }
            });
            ToolRegistry tools = skills.materializeTools(List.of("echo"));

            try (RougarouClient client = RougarouClient.builder(log)
                    .addAgentWorker(AgentWorkerConfig.builder()
                            .workerId("a")
                            .llmClient(new EchoLlmClient())
                            .toolRegistry(tools)
                            .build())
                    .addToolWorker(ToolWorkerConfig.builder()
                            .workerId("t")
                            .toolRegistry(tools)
                            .build())
                    .build()) {

                String sid = client.openSession(SessionConfig.builder()
                        .agentId("a")
                        .activeSkills(List.of("echo"))
                        .skillRegistry(skills)
                        .build());

                String reply = client.send(sid, "tool:echo {\"text\":\"hi\"}").get(10, TimeUnit.SECONDS);

                assertThat(reply).startsWith("Tool completed:");
                assertThat(reply).contains("\"echo\":\"hi\"");
            }
        }
    }

    @Test
    void conversationMemoryIsDerivedFromTheLog() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            try (RougarouClient client = RougarouClient.builder(log)
                    .addAgentWorker(AgentWorkerConfig.builder()
                            .workerId("a")
                            .llmClient(new EchoLlmClient())
                            .build())
                    .build()) {

                String sid = client.openSession(SessionConfig.builder().agentId("a").build());
                client.send(sid, "first").get(10, TimeUnit.SECONDS);
                client.send(sid, "second").get(10, TimeUnit.SECONDS);

                var conversation = client.memory().conversation(sid).get();

                assertThat(conversation).extracting("role")
                        .containsExactly("user", "assistant", "user", "assistant");
                assertThat(conversation).extracting("content")
                        .containsExactly("first", "Echo: first", "second", "Echo: second");
            }
        }
    }
}
