package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.bayou.BayouSystem;
import com.cajunsystems.bayou.Ref;
import com.cajunsystems.rougarou.core.Ids;
import com.cajunsystems.rougarou.core.RougarouLog;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gateway-layer entry point.
 *
 * <p>Maps session ids to live {@link SessionActor} refs. Sessions are dynamically spawned on first
 * use; they replay their own state from the rougarou log on start, so the manager carries no
 * durable state itself — it can be restarted at any time.
 */
public final class SessionManager implements AutoCloseable {

    private final BayouSystem bayou;
    private final RougarouLog log;
    private final Map<String, Ref<SessionMessage>> live = new ConcurrentHashMap<>();

    public SessionManager(BayouSystem bayou, RougarouLog log) {
        this.bayou = bayou;
        this.log = log;
    }

    /**
     * Open a brand new session. Returns the chosen session id and a ref for sending messages.
     */
    public OpenedSession openSession(SessionConfig config) {
        String sessionId = Ids.newSessionId();
        Ref<SessionMessage> ref = spawn(sessionId, config);
        return new OpenedSession(sessionId, ref);
    }

    /**
     * Resume an existing session by id. The actor will replay history on start. If the session
     * doesn't exist, it will be created with the supplied config.
     */
    public Ref<SessionMessage> attach(String sessionId, SessionConfig config) {
        return live.computeIfAbsent(sessionId, id -> spawn(id, config));
    }

    public Optional<Ref<SessionMessage>> lookup(String sessionId) {
        return Optional.ofNullable(live.get(sessionId));
    }

    private Ref<SessionMessage> spawn(String sessionId, SessionConfig config) {
        SessionActor actor = new SessionActor(sessionId, config, log);
        Ref<SessionMessage> ref = bayou.spawn("rougarou-session-" + sessionId, actor);
        live.put(sessionId, ref);
        return ref;
    }

    @Override
    public void close() {
        live.values().forEach(Ref::stop);
        live.clear();
    }

    public record OpenedSession(String sessionId, Ref<SessionMessage> ref) {}
}
