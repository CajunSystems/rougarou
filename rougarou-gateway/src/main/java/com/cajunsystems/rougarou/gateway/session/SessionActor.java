package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.rougarou.agent.skills.Skill;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.core.Ids;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionClosed;
import com.cajunsystems.rougarou.core.events.ToolCompleted;
import com.cajunsystems.rougarou.core.events.ToolFailed;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import com.cajunsystems.rougarou.core.events.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core gateway-layer actor.
 *
 * <p>Owns no domain state of its own — its world is reconstructed by replaying every event under
 * the {@code rougarou.session:&lt;sessionId&gt;} tag. The actor's mailbox serializes incoming user
 * input and live-event deliveries, ensuring decisions (which inference to request, which tool to
 * dispatch) happen under a single thread of control per session.
 *
 * <h2>Reliability</h2>
 * <ul>
 *   <li>Crash &amp; resume: the actor's only durable handle is the session log tag. Restarting a
 *       gateway re-spawns the actor and {@link #preStart} replays everything.</li>
 *   <li>Idempotency: every produced event carries a deterministic id (request id / tool call id);
 *       on replay we identify pending work by inspecting the state, not by mutating side effects.</li>
 *   <li>Multi-process: many gateway processes can run; sessions partition naturally by id and the
 *       actor system within each process owns a disjoint set.</li>
 * </ul>
 */
public final class SessionActor implements Actor<SessionMessage> {

    private final String sessionId;
    private final SessionConfig config;
    private final RougarouLog log;
    private final SessionState state = new SessionState();
    private final Map<String, java.util.concurrent.CompletableFuture<String>> waiters = new LinkedHashMap<>();
    /** Maps a just-appended UserMessage's id to the inference request id that should fire once the
     *  state has incorporated that message via the live subscription. */
    private final Map<String, String> pendingUserToInference = new LinkedHashMap<>();
    private SharedLog.Subscription liveSubscription;

    public SessionActor(String sessionId, SessionConfig config, RougarouLog log) {
        this.sessionId = sessionId;
        this.config = config;
        this.log = log;
    }

    public String sessionId() { return sessionId; }

    public SessionState state() { return state; }

    @Override
    public void preStart(BayouContext<SessionMessage> ctx) {
        // Replay everything that's already been written for this session.
        List<RougarouEvent> history = log.readSession(sessionId).join();
        history.forEach(state::apply);

        // From now on, deliver live events back into our own mailbox so they're processed serially
        // with user input. This is the key to making the actor's logic deterministic.
        liveSubscription = log.subscribeSessionTail(sessionId, ev ->
                ctx.self().tell(new SessionMessage.EventArrived(ev)));

        // If there's no history at all this is a fresh session — emit SessionCreated.
        if (history.isEmpty()) {
            log.append(new com.cajunsystems.rougarou.core.events.SessionCreated(
                    sessionId,
                    config.agentId(),
                    config.systemPrompt(),
                    config.metadata(),
                    Instant.now()
            )).join();
        }

        // If on restart we'd been mid-inference, the inference task will eventually complete and
        // be delivered back through the subscription, so we don't need to re-emit here.
    }

    @Override
    public void handle(SessionMessage message, BayouContext<SessionMessage> ctx) {
        switch (message) {
            case SessionMessage.UserInput input -> handleUserInput(input);
            case SessionMessage.EventArrived ev -> handleEvent(ev.event());
            case SessionMessage.Snapshot ignored -> ctx.reply(state);
            case SessionMessage.Close close -> handleClose(close, ctx);
        }
    }

    private void handleUserInput(SessionMessage.UserInput input) {
        if (state.status() == SessionState.Status.CLOSED) {
            input.reply().completeExceptionally(new IllegalStateException("session is closed"));
            return;
        }
        String messageId = Ids.newMessageId();
        String requestId = Ids.newRequestId();
        // Register the waiter against the request id; we'll complete it when the assistant message lands.
        waiters.put(requestId, input.reply());
        // Defer scheduling until our state has incorporated this UserMessage via live subscription —
        // otherwise the inference snapshot would lack the user's new turn.
        pendingUserToInference.put(messageId, requestId);

        log.append(new UserMessage(sessionId, messageId, input.content(), Instant.now())).join();
    }

    private void scheduleInference(String requestId) {
        List<String> availableTools = activeToolNames();
        List<InferenceRequested.Turn> turns = state.conversation().stream()
                .map(t -> new InferenceRequested.Turn(t.role(), t.content()))
                .toList();
        log.append(new InferenceRequested(
                sessionId,
                requestId,
                config.agentId(),
                turns,
                availableTools,
                config.metadata(),
                Instant.now()
        )).join();
    }

    private List<String> activeToolNames() {
        SkillRegistry registry = config.skillRegistry();
        if (registry == null) return List.of();
        List<String> names = new ArrayList<>();
        for (String skillName : config.activeSkills()) {
            registry.find(skillName).map(Skill::tools).ifPresent(tools -> {
                for (Tool t : tools) names.add(t.name());
            });
        }
        return names;
    }

    private void handleEvent(RougarouEvent event) {
        // Apply first so state reflects this event before we make scheduling decisions.
        state.apply(event);

        switch (event) {
            case UserMessage um -> {
                // Pending mapping is set by handleUserInput for client-driven turns; absent for
                // scheduler-driven turns (where there's no waiter). Either way, schedule inference.
                String requestId = pendingUserToInference.remove(um.messageId());
                if (requestId == null) requestId = Ids.newRequestId();
                scheduleInference(requestId);
            }
            case InferenceCompleted ic -> onInferenceCompleted(ic);
            case ToolCompleted tc -> onToolBoundaryReached(tc.requestId());
            case ToolFailed tf -> {
                if (!tf.retryable()) onToolBoundaryReached(tf.requestId());
            }
            default -> { /* other events are observed only */ }
        }
    }

    private void onInferenceCompleted(InferenceCompleted ic) {
        if (ic.hasToolCalls()) {
            // Dispatch every requested tool call. Tool workers append ToolCompleted/ToolFailed
            // back, which we'll handle in handleEvent → onToolBoundaryReached.
            for (var tc : ic.toolCalls()) {
                log.append(new ToolRequested(
                        sessionId,
                        ic.requestId(),
                        tc.toolCallId(),
                        tc.toolName(),
                        tc.argsJson(),
                        Instant.now()
                )).join();
            }
            return;
        }

        // No tool calls — finalize as an assistant message.
        String content = ic.content() == null ? "" : ic.content();
        AssistantMessage msg = new AssistantMessage(sessionId, Ids.newMessageId(), content, Instant.now());
        log.append(msg).join();
        // Complete any waiter listening for this request.
        var waiter = waiters.remove(ic.requestId());
        if (waiter != null) waiter.complete(content);
    }

    private void onToolBoundaryReached(String requestId) {
        // If every tool requested for this round-trip has finished, kick off another inference
        // so the LLM can incorporate tool results.
        if (state.pendingToolCalls().isEmpty()) {
            String nextRequestId = Ids.newRequestId();
            // Carry the original waiter forward — the user is waiting for the eventual assistant text.
            var waiter = waiters.remove(requestId);
            if (waiter != null) {
                waiters.put(nextRequestId, waiter);
            }
            scheduleInference(nextRequestId);
        }
    }

    private void handleClose(SessionMessage.Close close, BayouContext<SessionMessage> ctx) {
        if (state.status() == SessionState.Status.OPEN) {
            log.append(new SessionClosed(sessionId, close.reason(), Instant.now())).join();
        }
        // Fail any outstanding waiters.
        for (var w : waiters.values()) {
            w.completeExceptionally(new IllegalStateException("session closed before reply: " + close.reason()));
        }
        waiters.clear();
        if (liveSubscription != null) {
            liveSubscription.close();
            liveSubscription = null;
        }
        close.ack().complete(null);
        ctx.self().stop();
    }

    @Override
    public void postStop(BayouContext<SessionMessage> ctx) {
        if (liveSubscription != null) {
            liveSubscription.close();
            liveSubscription = null;
        }
    }
}
