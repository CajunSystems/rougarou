package com.cajunsystems.rougarou.gateway.session;

import com.cajunsystems.bayou.BayouContext;
import com.cajunsystems.bayou.actor.Actor;
import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.rougarou.agent.skills.Skill;
import com.cajunsystems.rougarou.agent.skills.SkillRegistry;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.core.Ids;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.RougarouTags;
import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceFailed;
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
        // Read history via raw log entries to capture the last sequence number. We then subscribe
        // from exactly lastSeqnum+1, closing the read-then-subscribe gap that exists when using
        // subscribeSessionTail: any event written between the readSession call and the tail
        // subscribe would land in neither window and be permanently lost. ScheduleWorker uses
        // the same pattern.
        var rawEntries = log.sharedLog().readAll(RougarouTags.session(sessionId)).join();
        long lastSeqnum = -1L;
        List<RougarouEvent> history = new ArrayList<>(rawEntries.size());
        for (var entry : rawEntries) {
            RougarouEvent ev = log.decode(entry);
            history.add(ev);
            state.apply(ev);
            lastSeqnum = entry.seqnum();
        }

        // Subscribe from the next position — events appended after the read above (including any
        // that arrived in the gap on a previous run) are delivered here with no hole.
        liveSubscription = log.subscribeSessionFrom(sessionId, lastSeqnum, ev ->
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
            return;
        }

        // Crash-recovery: if we died after appending a UserMessage / ToolCompleted but before the
        // live subscription delivered it back to schedule the follow-up inference, the session
        // would be stuck forever — replay only mutates state, it doesn't drive scheduling. Detect
        // those orphans by inspecting state and kick the next step ourselves.
        recoverPendingWork(history);
    }

    /**
     * After replay, if state shows pending work that has no in-flight follow-up event, restart
     * the appropriate next step. Specifically:
     * <ul>
     *   <li>A pending inference request id with no terminal {@link InferenceCompleted} or
     *       non-retryable {@link InferenceFailed}: re-emit {@link InferenceRequested} (idempotent
     *       on the worker side because the request id is unchanged).</li>
     *   <li>No pending inference, but the trailing user/tool turn never produced an
     *       {@link InferenceRequested}: emit a fresh one with a new request id.</li>
     * </ul>
     */
    private void recoverPendingWork(List<RougarouEvent> history) {
        if (state.status() == SessionState.Status.CLOSED) return;

        if (state.pendingInferenceRequestId() != null) {
            // The original request was logged but no terminal completion event followed.
            // Re-emit it so a worker picks it back up. (No client waiter — the original caller
            // is gone; the response will land as an AssistantMessage on the log.)
            scheduleInference(state.pendingInferenceRequestId());
            return;
        }

        if (!state.pendingToolCalls().isEmpty()) {
            // The live subscription covers position lastSeqnum+1 onward (no gap), so any
            // ToolCompleted written after the history read — including one written in what used
            // to be the tail-subscribe gap — will be delivered and handleEvent will call
            // onToolBoundaryReached normally. Nothing to do here.
            return;
        }

        // No request id pending and no tool calls outstanding. Check whether the trailing
        // domain event is one that should have triggered an inference.
        RougarouEvent trailing = lastTriggerEvent(history);
        if (trailing instanceof UserMessage || trailing instanceof ToolCompleted) {
            scheduleInference(Ids.newRequestId());
        }
    }

    private static RougarouEvent lastTriggerEvent(List<RougarouEvent> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            RougarouEvent ev = history.get(i);
            if (ev instanceof UserMessage
                    || ev instanceof ToolCompleted
                    || ev instanceof AssistantMessage
                    || ev instanceof InferenceRequested
                    || ev instanceof InferenceCompleted
                    || ev instanceof InferenceFailed) {
                return ev;
            }
        }
        return null;
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
        // Snapshot dedupe-relevant flags BEFORE applying — once apply runs the dedupe sets are
        // updated and we'd lose the "is this a duplicate" signal.
        boolean isDuplicateUserMessage = event instanceof UserMessage um
                && state.hasAppliedUserMessage(um.messageId());
        boolean isDuplicateInferenceCompleted = event instanceof InferenceCompleted ic
                && state.hasCompletedInference(ic.requestId());

        // Apply first so state reflects this event before we make scheduling decisions.
        state.apply(event);

        switch (event) {
            case UserMessage um -> {
                if (isDuplicateUserMessage) break;
                // Pending mapping is set by handleUserInput for client-driven turns; absent for
                // scheduler-driven turns (where there's no waiter). Either way, schedule inference.
                String requestId = pendingUserToInference.remove(um.messageId());
                if (requestId == null) requestId = Ids.newRequestId();
                scheduleInference(requestId);
            }
            case InferenceCompleted ic -> {
                // Defense in depth: AgentWorker also checks before calling the LLM, but a
                // duplicate that slipped through must not produce a second AssistantMessage.
                if (isDuplicateInferenceCompleted) break;
                onInferenceCompleted(ic);
            }
            case InferenceFailed inf -> {
                // A retryable failure means another attempt will follow — keep the waiter parked.
                // Non-retryable means we've exhausted the budget; complete the waiter with an
                // exception so callers don't hang.
                if (!inf.retryable()) {
                    var waiter = waiters.remove(inf.requestId());
                    if (waiter != null) {
                        waiter.completeExceptionally(new InferenceTerminallyFailedException(
                                inf.errorType(), inf.message(), inf.attempt()));
                    }
                }
            }
            case ToolCompleted tc -> onToolBoundaryReached(tc.requestId());
            case ToolFailed tf -> {
                if (!tf.retryable()) {
                    // The tool will not complete — surface this to any caller waiting on the
                    // owning inference request and clean up the boundary so we don't deadlock
                    // on a never-arriving result.
                    var waiter = waiters.remove(tf.requestId());
                    if (waiter != null) {
                        waiter.completeExceptionally(new ToolTerminallyFailedException(
                                tf.toolName(), tf.errorType(), tf.message(), tf.attempt()));
                    }
                    onToolBoundaryReached(tf.requestId());
                }
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
