package com.cajunsystems.rougarou.client.http;

import com.cajunsystems.rougarou.client.sdk.RougarouClient;
import com.cajunsystems.rougarou.gateway.memory.AgentMemory;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JSON-over-HTTP front end for {@link RougarouClient}, built on Javalin.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST   /sessions} → {@code {"sessionId":"..."}}</li>
 *   <li>{@code POST   /sessions/{id}/messages} body {@code {"content":"..."}} → {@code {"reply":"..."}}</li>
 *   <li>{@code GET    /sessions/{id}/conversation} → {@code {"turns":[...]}}</li>
 *   <li>{@code DELETE /sessions/{id}} → {@code {"closed":true}}</li>
 * </ul>
 *
 * <p>Javalin runs on top of Jetty with virtual threads enabled so blocking on the gateway is
 * cheap. JSON encoding/decoding uses Jackson with Java-time module support.
 */
public final class RougarouHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RougarouHttpServer.class);

    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final RougarouClient client;
    private final SessionConfig defaultConfig;
    private final Duration sendTimeout;
    private final int boundPort;
    private final Javalin app;

    public RougarouHttpServer(RougarouClient client, SessionConfig defaultConfig, int port) {
        this(client, defaultConfig, port, DEFAULT_SEND_TIMEOUT);
    }

    public RougarouHttpServer(RougarouClient client, SessionConfig defaultConfig, int port, Duration sendTimeout) {
        this.client = client;
        this.defaultConfig = defaultConfig;
        this.sendTimeout = sendTimeout;
        this.boundPort = port;

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        this.app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(mapper, /* useLogging = */ false));
            config.showJavalinBanner = false;
        });

        registerRoutes();
    }

    /** The underlying Javalin instance. Useful for adding custom routes / middleware. */
    public Javalin app() { return app; }

    public int port() { return app.port(); }

    public void start() {
        app.start(boundPort);
        log.info("Rougarou HTTP server listening on :{}", app.port());
    }

    @Override
    public void close() {
        app.stop();
    }

    public Map<String, Object> info() {
        return Map.of("port", app.port());
    }

    public static RougarouHttpServer start(RougarouClient client, SessionConfig defaultConfig, int port) {
        RougarouHttpServer s = new RougarouHttpServer(client, defaultConfig, port);
        s.start();
        return s;
    }

    public static Duration recommendedClientTimeout() { return DEFAULT_SEND_TIMEOUT; }

    private void registerRoutes() {
        app.post("/sessions", ctx -> {
            // The only route that creates a session.
            String sid = client.openSession(defaultConfig);
            ctx.status(201).json(Map.of("sessionId", sid));
        });

        app.post("/sessions/{id}/messages", ctx -> {
            String sid = ctx.pathParam("id");
            // 404 instead of silently creating — POST /sessions is the explicit creation route.
            // Without this, a typo in the path would manufacture a phantom session.
            if (!client.sessionExists(sid).join()) {
                ctx.status(404).json(Map.of("error", "session_not_found", "sessionId", sid));
                return;
            }
            client.resumeSession(sid, defaultConfig);
            MessageRequest req = ctx.bodyAsClass(MessageRequest.class);
            try {
                String reply = client.send(sid, req.content())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                ctx.json(Map.of("reply", reply));
            } catch (TimeoutException te) {
                ctx.status(504).json(Map.of("error", "timeout",
                        "message", "no reply within " + sendTimeout));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                ctx.status(500).json(Map.of("error", cause.getClass().getSimpleName(),
                        "message", cause.getMessage() == null ? "" : cause.getMessage()));
            }
        });

        app.get("/sessions/{id}/conversation", ctx -> {
            String sid = ctx.pathParam("id");
            // Read-only must NOT mutate the log. resumeSession would spawn an actor whose
            // preStart writes a SessionCreated for an unknown id.
            if (!client.sessionExists(sid).join()) {
                ctx.status(404).json(Map.of("error", "session_not_found", "sessionId", sid));
                return;
            }
            // Conversation projection reads from the log directly, no need to spawn the actor.
            List<AgentMemory.Turn> turns = client.memory().conversation(sid).join();
            ctx.json(Map.of("turns", turns));
        });

        app.delete("/sessions/{id}", ctx -> {
            String sid = ctx.pathParam("id");
            if (!client.sessionExists(sid).join()) {
                ctx.status(404).json(Map.of("error", "session_not_found", "sessionId", sid));
                return;
            }
            client.resumeSession(sid, defaultConfig);
            client.close(sid, "http-request")
                    .orTimeout(DEFAULT_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            ctx.json(Map.of("closed", true));
        });

        app.exception(NoSuchElementException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("error", "not_found", "message", e.getMessage())));
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", "bad_request", "message", e.getMessage())));
    }

    /** Body shape for {@code POST /sessions/{id}/messages}. */
    public record MessageRequest(String content) {}
}
