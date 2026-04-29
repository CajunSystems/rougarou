package com.cajunsystems.rougarou.client;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.agent.llm.EchoLlmClient;
import com.cajunsystems.rougarou.agent.worker.AgentWorkerConfig;
import com.cajunsystems.rougarou.client.http.RougarouHttpServer;
import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RougarouHttpServerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void unknownSessionRoutesReturn404WithoutMutatingTheLog() throws Exception {
        // Read-only and lifecycle routes for an unknown session id MUST NOT silently spawn an
        // actor (whose preStart would write a phantom SessionCreated). They return 404 instead
        // and leave the log untouched.
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build());
             RougarouClient client = RougarouClient.builder(log)
                     .addAgentWorker(AgentWorkerConfig.builder()
                             .workerId("a").llmClient(new EchoLlmClient()).build())
                     .build();
             RougarouHttpServer server = new RougarouHttpServer(client,
                     SessionConfig.builder().agentId("a").build(),
                     /* port = */ 0)) {
            server.start();
            int port = server.port();

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://localhost:" + port;
            String unknown = "ses-does-not-exist";

            // Snapshot how many entries the log started with — the conversation tag should still
            // have zero events for the unknown session after each call below.
            assertThat(client.sessionExists(unknown).join()).isFalse();

            HttpResponse<String> getConv = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/sessions/" + unknown + "/conversation"))
                            .GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(getConv.statusCode()).isEqualTo(404);

            HttpResponse<String> postMsg = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/sessions/" + unknown + "/messages"))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hi\"}"))
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(postMsg.statusCode()).isEqualTo(404);

            HttpResponse<String> del = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/sessions/" + unknown))
                            .DELETE().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(del.statusCode()).isEqualTo(404);

            // Crucially: the log has NOT been mutated for that id.
            assertThat(client.sessionExists(unknown).join())
                    .as("unknown-session calls must not create phantom log entries")
                    .isFalse();
        }
    }

    @Test
    void postsAndRetrievesAConversationOverHttp() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build());
             RougarouClient client = RougarouClient.builder(log)
                     .addAgentWorker(AgentWorkerConfig.builder()
                             .workerId("a").llmClient(new EchoLlmClient()).build())
                     .build();
             RougarouHttpServer server = new RougarouHttpServer(client,
                     SessionConfig.builder().agentId("a").build(),
                     /* port = */ 0)) {
            server.start();
            int port = server.port();

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://localhost:" + port;

            // Open a session.
            HttpResponse<String> open = http.send(HttpRequest.newBuilder(URI.create(base + "/sessions"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(open.statusCode()).isEqualTo(201);
            String sid = json.readTree(open.body()).get("sessionId").asText();
            assertThat(sid).startsWith("ses-");

            // Send a message — payload includes a unicode character that the old hand-rolled
            // JSON parser would have mangled.
            String body = "{\"content\":\"caf\\u00e9 ☕\"}";
            HttpResponse<String> send = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/sessions/" + sid + "/messages"))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(send.statusCode()).isEqualTo(200);
            JsonNode replyJson = json.readTree(send.body());
            assertThat(replyJson.get("reply").asText()).isEqualTo("Echo: café ☕");

            // Read back the conversation.
            HttpResponse<String> conv = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/sessions/" + sid + "/conversation"))
                            .GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(conv.statusCode()).isEqualTo(200);
            JsonNode turns = json.readTree(conv.body()).get("turns");
            assertThat(turns).hasSize(2);
            assertThat(turns.get(0).get("role").asText()).isEqualTo("user");
            assertThat(turns.get(0).get("content").asText()).isEqualTo("café ☕");
            assertThat(turns.get(1).get("role").asText()).isEqualTo("assistant");
            assertThat(turns.get(1).get("content").asText()).isEqualTo("Echo: café ☕");
        }
    }
}
