package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.gumbo.core.LogPosition;
import com.cajunsystems.rougarou.agent.llm.LlmClient;
import com.cajunsystems.rougarou.agent.llm.LlmException;
import com.cajunsystems.rougarou.agent.llm.LlmRequest;
import com.cajunsystems.rougarou.agent.llm.LlmResponse;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.agent.tools.ToolRegistry;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.RougarouTags;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceFailed;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to the {@code rougarou.inference} task tag, calls the configured
 * {@link LlmClient}, and appends an {@link InferenceCompleted} or {@link InferenceFailed} event
 * back to the log.
 *
 * <p>The worker is stateless across requests. State for each request lives entirely in the
 * inference event payload (full conversation snapshot), so any number of workers can be running
 * and another can pick up work after a crash.
 *
 * <h2>Idempotency</h2>
 * <ul>
 *   <li><b>In-process:</b> an {@link #inFlight} set blocks two concurrent calls of the same
 *       worker from double-handling the same {@code requestId}.</li>
 *   <li><b>Cross-restart:</b> before invoking the LLM the worker scans the request's session
 *       tag for a terminal completion ({@link InferenceCompleted} or non-retryable
 *       {@link InferenceFailed}) carrying the same {@code requestId}; if found, it skips the
 *       LLM call entirely. This prevents a duplicate paid LLM invocation when a checkpoint
 *       failed to persist before the previous restart.</li>
 *   <li><b>Cross-process / defense-in-depth:</b> {@code SessionState} also dedupes
 *       {@link InferenceCompleted} by {@code requestId} so a duplicate completion that slips
 *       past the worker-side guard still doesn't append a duplicate {@code AssistantMessage}.</li>
 * </ul>
 */
public final class AgentWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    private final AgentWorkerConfig config;
    private final RougarouLog rougarouLog;
    private final SharedLog sharedLog;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private SharedLog.Subscription subscription;

    public AgentWorker(AgentWorkerConfig config, RougarouLog rougarouLog) {
        this.config = config;
        this.rougarouLog = rougarouLog;
        this.sharedLog = rougarouLog.sharedLog();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;

        long checkpoint = readCheckpoint();
        log.info("Agent worker {} starting from checkpoint seqnum={}", config.workerId(), checkpoint);

        LogPosition from = checkpoint < 0 ? LogPosition.BEGINNING : new LogPosition(checkpoint + 1);
        subscription = sharedLog.subscribe(RougarouTags.inferenceTasks(), from, entry -> {
            try {
                RougarouEvent event = rougarouLog.decode(entry);
                if (event instanceof InferenceRequested req) {
                    if (inFlight.add(req.requestId())) {
                        try {
                            handle(req);
                            saveCheckpoint(entry.seqnum());
                        } finally {
                            inFlight.remove(req.requestId());
                        }
                    }
                }
            } catch (Throwable t) {
                log.error("agent worker {} failed handling entry seqnum={}", config.workerId(), entry.seqnum(), t);
            }
        });
    }

    private void handle(InferenceRequested req) {
        // Cross-restart idempotency: if a previous worker (this one or a peer) already wrote a
        // terminal completion or non-retryable failure for this request id, don't re-invoke the
        // LLM. This guards against a checkpoint that failed to persist (the in-process inFlight
        // set is reset on restart, so without this scan the worker would re-process and produce
        // a duplicate response — and a duplicate paid LLM call).
        if (alreadyTerminallyHandled(req)) {
            log.info("agent worker {} skipping requestId={} — already has a terminal completion on session tag",
                    config.workerId(), req.requestId());
            return;
        }

        ToolRegistry tools = config.toolRegistry();
        List<LlmRequest.Turn> turns = req.conversation().stream()
                .map(t -> new LlmRequest.Turn(t.role(), t.content()))
                .toList();
        List<LlmRequest.ToolSpec> toolSpecs = new ArrayList<>();
        for (String toolName : req.availableTools()) {
            tools.find(toolName).ifPresent(t ->
                    toolSpecs.add(new LlmRequest.ToolSpec(t.name(), t.description(), t.inputSchemaJson())));
        }
        LlmRequest llmRequest = new LlmRequest(config.model(), turns, toolSpecs, req.metadata());

        Throwable lastError = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                LlmResponse resp = config.llmClient().complete(llmRequest);
                List<InferenceCompleted.ToolCall> toolCalls = resp.toolCalls().stream()
                        .map(tc -> new InferenceCompleted.ToolCall(tc.id(), tc.name(), tc.argsJson()))
                        .toList();
                rougarouLog.append(new InferenceCompleted(
                        req.sessionId(),
                        req.requestId(),
                        resp.content() == null ? "" : resp.content(),
                        toolCalls,
                        Instant.now()
                )).join();
                return;
            } catch (LlmException e) {
                lastError = e;
                rougarouLog.append(new InferenceFailed(
                        req.sessionId(),
                        req.requestId(),
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        attempt,
                        e.isRetryable() && attempt < config.maxAttempts(),
                        Instant.now()
                )).join();
                if (!e.isRetryable() || attempt >= config.maxAttempts()) return;
                sleepBackoff(attempt);
            } catch (RuntimeException e) {
                lastError = e;
                rougarouLog.append(new InferenceFailed(
                        req.sessionId(),
                        req.requestId(),
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        attempt,
                        false,
                        Instant.now()
                )).join();
                return;
            }
        }
        log.error("agent worker {} exhausted retries for request {}", config.workerId(), req.requestId(), lastError);
    }

    /**
     * True if the session log already contains a terminal completion or a non-retryable failure
     * for this request id. Read scope is bounded by the session tag (per-session, not the full
     * inference task queue), so cost scales with one session's history.
     */
    private boolean alreadyTerminallyHandled(InferenceRequested req) {
        try {
            return rougarouLog.readSession(req.sessionId()).join().stream().anyMatch(e ->
                    (e instanceof InferenceCompleted ic && req.requestId().equals(ic.requestId()))
                            || (e instanceof InferenceFailed inf
                                    && req.requestId().equals(inf.requestId())
                                    && !inf.retryable()));
        } catch (Exception e) {
            // If the lookup fails, fall through and re-process — better to risk a duplicate than
            // to silently drop a request because the read transiently failed.
            log.warn("agent worker {} failed reading session tag for idempotency check (requestId={})",
                    config.workerId(), req.requestId(), e);
            return false;
        }
    }

    private void sleepBackoff(int attempt) {
        long ms = (long) (config.initialBackoff().toMillis() * Math.pow(config.backoffMultiplier(), attempt - 1));
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private long readCheckpoint() {
        try {
            byte[] data = sharedLog.getView(RougarouTags.inferenceTasks())
                    .getValue("checkpoint:" + config.workerId()).join();
            if (data == null || data.length < 8) return -1L;
            return ByteBuffer.wrap(data).getLong();
        } catch (Exception e) {
            log.warn("failed to read checkpoint for {}, starting from beginning", config.workerId(), e);
            return -1L;
        }
    }

    private void saveCheckpoint(long seqnum) {
        try {
            sharedLog.getView(RougarouTags.inferenceTasks())
                    .setValue("checkpoint:" + config.workerId(),
                            ByteBuffer.allocate(8).putLong(seqnum).array())
                    .join();
        } catch (Exception e) {
            log.warn("failed to save checkpoint for {}", config.workerId(), e);
        }
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) return;
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
