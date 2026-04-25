package com.cajunsystems.rougarou.examples;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.agent.worker.ToolWorkerConfig;
import com.cajunsystems.rougarou.client.http.RougarouHttpServer;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Stands the harness up behind an HTTP listener. Useful for poking at the system with curl.
 *
 * <pre>{@code
 * # POST /sessions      → {"sessionId":"ses-..."}
 * curl -X POST http://localhost:8080/sessions
 *
 * # POST /sessions/{id}/messages
 * curl -X POST http://localhost:8080/sessions/<id>/messages \
 *      -H 'content-type: application/json' \
 *      -d '{"content":"Hello"}'
 *
 * # GET /sessions/{id}/conversation
 * curl http://localhost:8080/sessions/<id>/conversation
 * }</pre>
 */
public final class HttpExample {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        var sharedLog = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build());

        SkillRegistry skills = new SkillRegistry().register(new EchoSkill());
        ToolRegistry tools = skills.materializeTools(List.of("echo"));

        RougarouClient client = RougarouClient.builderOwning(sharedLog)
                .addAgentWorker(AgentWorkerConfig.builder()
                        .workerId("agent-1")
                        .llmClient(new EchoLlmClient())
                        .toolRegistry(tools)
                        .build())
                .addToolWorker(ToolWorkerConfig.builder()
                        .workerId("tool-1")
                        .toolRegistry(tools)
                        .build())
                .build();

        SessionConfig defaultConfig = SessionConfig.builder()
                .agentId("echo-agent")
                .systemPrompt("You are a friendly echo agent.")
                .activeSkills(List.of("echo"))
                .skillRegistry(skills)
                .build();

        RougarouHttpServer server = RougarouHttpServer.start(client, defaultConfig, port);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            client.close();
            shutdown.countDown();
        }));

        System.out.println("Rougarou listening on " + server.info());
        shutdown.await();
    }
}
