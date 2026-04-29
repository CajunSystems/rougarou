package com.cajunsystems.rougarou.gateway.controller;

import com.cajunsystems.bayou.Ref;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.gateway.memory.AgentMemory;
import com.cajunsystems.rougarou.gateway.session.SessionConfig;
import com.cajunsystems.rougarou.gateway.session.SessionManager;
import com.cajunsystems.rougarou.gateway.session.SessionMessage;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

/**
 * Façade exposed to the client layer. Hides the actor model behind a small set of futures.
 *
 * <p>Methods are safe to call from any thread — the underlying {@link SessionManager} routes work
 * onto per-session bayou actors that serialize execution.
 */
public final class GatewayController {

    private final SessionManager sessions;
    private final AgentMemory memory;

    public GatewayController(SessionManager sessions, RougarouLog log) {
        this.sessions = sessions;
        this.memory = new AgentMemory(log);
    }

    public String openSession(SessionConfig config) {
        return sessions.openSession(config).sessionId();
    }

    public void resumeSession(String sessionId, SessionConfig config) {
        sessions.attach(sessionId, config);
    }

    /**
     * Send a user turn and wait for the assistant's response. Resolves with the assistant's
     * final text once tool-call rounds (if any) are complete.
     */
    public CompletableFuture<String> send(String sessionId, String content) {
        Ref<SessionMessage> ref = sessions.lookup(sessionId)
                .orElseThrow(() -> new NoSuchElementException("session not attached: " + sessionId));
        CompletableFuture<String> reply = new CompletableFuture<>();
        ref.tell(new SessionMessage.UserInput(content, reply));
        return reply;
    }

    public CompletableFuture<Void> close(String sessionId, String reason) {
        Ref<SessionMessage> ref = sessions.lookup(sessionId)
                .orElseThrow(() -> new NoSuchElementException("session not attached: " + sessionId));
        CompletableFuture<Void> ack = new CompletableFuture<>();
        ref.tell(new SessionMessage.Close(reason, ack));
        return ack;
    }

    public CompletableFuture<List<AgentMemory.Turn>> conversation(String sessionId) {
        return memory.conversation(sessionId);
    }

    public AgentMemory memory() { return memory; }
}
