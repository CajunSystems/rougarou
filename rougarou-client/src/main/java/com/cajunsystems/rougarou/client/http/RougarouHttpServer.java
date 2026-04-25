package com.cajunsystems.rougarou.client.http;

import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny JSON-over-HTTP front end for {@link RougarouClient}.
 *
 * <p>Endpoints (all use a hand-rolled minimal JSON shape — no external dep):
 * <ul>
 *   <li>{@code POST /sessions} → {@code {"sessionId":"..."}}</li>
 *   <li>{@code POST /sessions/{id}/messages} body {@code {"content":"..."}} → {@code {"reply":"..."}}</li>
 *   <li>{@code GET  /sessions/{id}/conversation} → {@code {"turns":[{"role":..,"content":..}, ...]}}</li>
 *   <li>{@code DELETE /sessions/{id}} → {@code {"closed":true}}</li>
 * </ul>
 *
 * <p>The server uses virtual threads for request handling so blocking on the gateway is cheap.
 */
public final class RougarouHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RougarouHttpServer.class);

    private final RougarouClient client;
    private final SessionConfig defaultConfig;
    private final HttpServer server;

    public RougarouHttpServer(RougarouClient client, SessionConfig defaultConfig, int port) throws IOException {
        this.client = client;
        this.defaultConfig = defaultConfig;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/sessions", this::handleSessions);
    }

    public int port() { return server.getAddress().getPort(); }

    public void start() {
        server.start();
        log.info("Rougarou HTTP server listening on {}", server.getAddress());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleSessions(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String[] parts = path.split("/");
            // [0]="" [1]="sessions" [2]=id? [3]=subresource?
            if (parts.length == 2 && method.equals("POST")) {
                String sid = client.openSession(defaultConfig);
                writeJson(exchange, 201, "{\"sessionId\":\"" + sid + "\"}");
                return;
            }
            if (parts.length == 4 && method.equals("POST") && parts[3].equals("messages")) {
                String sid = parts[2];
                ensureAttached(sid);
                String body = readBody(exchange);
                String content = jsonExtract(body, "content");
                String reply = client.send(sid, content)
                        .orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).join();
                writeJson(exchange, 200, "{\"reply\":" + jsonString(reply) + "}");
                return;
            }
            if (parts.length == 4 && method.equals("GET") && parts[3].equals("conversation")) {
                String sid = parts[2];
                ensureAttached(sid);
                List<com.cajunsystems.rougarou.gateway.memory.AgentMemory.Turn> turns =
                        client.memory().conversation(sid).join();
                StringBuilder sb = new StringBuilder("{\"turns\":[");
                for (int i = 0; i < turns.size(); i++) {
                    var t = turns.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"role\":").append(jsonString(t.role()))
                            .append(",\"content\":").append(jsonString(t.content()))
                            .append(",\"ts\":").append(t.timestampMillis())
                            .append('}');
                }
                sb.append("]}");
                writeJson(exchange, 200, sb.toString());
                return;
            }
            if (parts.length == 3 && method.equals("DELETE")) {
                String sid = parts[2];
                ensureAttached(sid);
                client.close(sid, "http-request").orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
                writeJson(exchange, 200, "{\"closed\":true}");
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            log.error("error handling {}", exchange.getRequestURI(), e);
            writeJson(exchange, 500, "{\"error\":" + jsonString(e.getMessage() == null ? "internal" : e.getMessage()) + "}");
        } finally {
            exchange.close();
        }
    }

    private void ensureAttached(String sessionId) {
        client.resumeSession(sessionId, defaultConfig);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Extract a top-level string field from a tiny JSON object. Not robust — fine for this demo. */
    static String jsonExtract(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx);
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    default -> out.append(n);
                }
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Convenience factory: starts a server with sane defaults. */
    public static RougarouHttpServer start(RougarouClient client, SessionConfig defaultConfig, int port) throws IOException {
        RougarouHttpServer s = new RougarouHttpServer(client, defaultConfig, port);
        s.start();
        return s;
    }

    /** Suggested timeouts when calling this server from another process. */
    public static Duration recommendedClientTimeout() {
        return Duration.ofSeconds(60);
    }

    public Map<String, Object> info() {
        return Map.of(
                "address", server.getAddress().toString(),
                "port", server.getAddress().getPort()
        );
    }
}
