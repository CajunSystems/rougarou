package com.cajunsystems.rougarou.core;

import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.gumbo.core.AppendRequest;
import com.cajunsystems.gumbo.core.AppendResult;
import com.cajunsystems.gumbo.core.LogEntry;
import com.cajunsystems.gumbo.core.LogPosition;
import com.cajunsystems.gumbo.core.LogTag;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.ScheduleCancelled;
import com.cajunsystems.rougarou.core.events.ScheduleFired;
import com.cajunsystems.rougarou.core.events.ScheduleRequested;
import com.cajunsystems.rougarou.core.events.SessionClosed;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolRequested;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Thin facade around a {@link SharedLog} that knows how to (de)serialize {@link RougarouEvent}
 * and how to fan-out tag each variant so the gateway, agent workers, and tool workers each see
 * what they need.
 *
 * <p>Routing rules:
 * <ul>
 *   <li>Every event is tagged with the per-session tag {@code rougarou.session:&lt;sessionId&gt;}
 *       so {@link com.cajunsystems.rougarou.core.RougarouTags#session(String)} replay rebuilds
 *       conversation state.</li>
 *   <li>Lifecycle events ({@link SessionCreated}, {@link SessionClosed}) are also tagged with
 *       {@link RougarouTags#sessions()} for cross-session listeners.</li>
 *   <li>{@link InferenceRequested} is tagged with {@link RougarouTags#inferenceTasks()} so
 *       agent workers can subscribe to a single shared queue.</li>
 *   <li>{@link ToolRequested} is tagged with {@link RougarouTags#toolTasks()} so tool workers
 *       can subscribe to a single shared queue.</li>
 *   <li>Every event is also tagged with {@link RougarouTags#audit()} for global observability.</li>
 * </ul>
 */
public final class RougarouLog {

    private final SharedLog log;
    private final RougarouEventSerializer serializer;

    public RougarouLog(SharedLog log) {
        this(log, new RougarouEventSerializer());
    }

    public RougarouLog(SharedLog log, RougarouEventSerializer serializer) {
        this.log = log;
        this.serializer = serializer;
    }

    public SharedLog sharedLog() { return log; }

    public RougarouEventSerializer serializer() { return serializer; }

    /** Append an event and fan-out tag it according to {@link RougarouLog routing rules}. */
    public CompletableFuture<AppendResult> append(RougarouEvent event) {
        Set<LogTag> tags = new LinkedHashSet<>();
        tags.add(RougarouTags.session(event.sessionId()));
        tags.add(RougarouTags.audit());

        if (event instanceof SessionCreated || event instanceof SessionClosed) {
            tags.add(RougarouTags.sessions());
        }
        if (event instanceof InferenceRequested) {
            tags.add(RougarouTags.inferenceTasks());
        }
        if (event instanceof ToolRequested) {
            tags.add(RougarouTags.toolTasks());
        }
        if (event instanceof ScheduleRequested
                || event instanceof ScheduleCancelled
                || event instanceof ScheduleFired) {
            tags.add(RougarouTags.schedule());
        }

        byte[] bytes = serializer.serialize(event);
        return log.append(AppendRequest.to(tags, bytes));
    }

    /** Read all events for a session in append order. */
    public CompletableFuture<List<RougarouEvent>> readSession(String sessionId) {
        return log.readAll(RougarouTags.session(sessionId))
                .thenApply(entries -> entries.stream()
                        .map(this::decode)
                        .toList());
    }

    /** Decode one entry into its event variant. */
    public RougarouEvent decode(LogEntry entry) {
        return serializer.deserialize(entry.dataUnsafe());
    }

    /** Subscribe to every new event for a session, including backlog. */
    public SharedLog.Subscription subscribeSession(String sessionId, Consumer<RougarouEvent> handler) {
        return log.subscribe(RougarouTags.session(sessionId), LogPosition.BEGINNING, e -> handler.accept(decode(e)));
    }

    /** Subscribe only to future events for a session. */
    public SharedLog.Subscription subscribeSessionTail(String sessionId, Consumer<RougarouEvent> handler) {
        return log.subscribeTail(RougarouTags.session(sessionId), e -> handler.accept(decode(e)));
    }

    /**
     * Subscribe to session events starting from the position immediately after {@code afterSeqnum}.
     * Pass {@code -1} to start from the very beginning.
     *
     * <p>Use this instead of {@link #subscribeSessionTail} whenever history is read first. Tail
     * subscribe opens a gap: any event written after the {@code readSession} call but before
     * {@code subscribeTail} completes lands in neither window and is permanently lost. By
     * subscribing from {@code lastReadSeqnum + 1} the two windows are contiguous with no gap.
     */
    public SharedLog.Subscription subscribeSessionFrom(String sessionId, long afterSeqnum,
            Consumer<RougarouEvent> handler) {
        LogPosition from = afterSeqnum < 0 ? LogPosition.BEGINNING : new LogPosition(afterSeqnum + 1);
        return log.subscribe(RougarouTags.session(sessionId), from, e -> handler.accept(decode(e)));
    }

    /** Subscribe to the cross-session lifecycle stream. */
    public SharedLog.Subscription subscribeSessions(Consumer<RougarouEvent> handler) {
        return log.subscribeTail(RougarouTags.sessions(), e -> handler.accept(decode(e)));
    }
}
