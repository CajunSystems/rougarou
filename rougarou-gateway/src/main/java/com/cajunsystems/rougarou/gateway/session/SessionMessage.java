package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.rougarou.core.events.RougarouEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Messages accepted by the {@link SessionActor session actor}.
 *
 * <p>Sealed so the actor's {@code handle} method can exhaustively switch.
 */
public sealed interface SessionMessage {

    /** A new user-supplied turn. The actor appends a {@code UserMessage} event and kicks off inference. */
    record UserInput(String content, CompletableFuture<String> reply) implements SessionMessage {}

    /** Internal: a new event arrived on the session log tag. Wraps live + replay deliveries. */
    record EventArrived(RougarouEvent event) implements SessionMessage {}

    /** Gracefully close the session and stop the actor. */
    record Close(String reason, CompletableFuture<Void> ack) implements SessionMessage {}

    /** Snapshot query — actor replies via {@link com.cajunsystems.bayou.BayouContext#reply}. */
    record Snapshot() implements SessionMessage {}
}
