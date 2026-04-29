package com.cajunsystems.rougarou.agent.worker;

import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.gumbo.core.LogPosition;
import com.cajunsystems.rougarou.agent.tools.Tool;
import com.cajunsystems.rougarou.agent.tools.ToolException;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.RougarouTags;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.ToolCompleted;
import com.cajunsystems.rougarou.core.events.ToolFailed;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to {@code rougarou.tool} task tag and dispatches each request to a registered
 * {@link Tool}. Mirrors {@link AgentWorker} but for tool execution.
 *
 * <h2>Idempotency</h2>
 * <ul>
 *   <li><b>In-process:</b> {@link #inFlight} blocks two concurrent invocations of the same
 *       worker from double-handling the same {@code toolCallId}.</li>
 *   <li><b>Cross-restart:</b> before invoking the tool the worker scans the request's session
 *       tag for a terminal {@link ToolCompleted} or non-retryable {@link ToolFailed} carrying
 *       the same {@code toolCallId}; if found, it skips the invocation. Critical for tools with
 *       side effects (payments, emails, file writes) — without this, a {@code saveCheckpoint}
 *       failure causes the tool to run twice on restart.</li>
 *   <li><b>Cross-process:</b> the same scan covers it. Two workers racing past the scan would
 *       both invoke the tool; for that case tools should themselves be idempotent.</li>
 * </ul>
 */
public final class ToolWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolWorker.class);

    private final ToolWorkerConfig config;
    private final RougarouLog rougarouLog;
    private final SharedLog sharedLog;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private SharedLog.Subscription subscription;

    public ToolWorker(ToolWorkerConfig config, RougarouLog rougarouLog) {
        this.config = config;
        this.rougarouLog = rougarouLog;
        this.sharedLog = rougarouLog.sharedLog();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;

        long checkpoint = readCheckpoint();
        log.info("Tool worker {} starting from checkpoint seqnum={}", config.workerId(), checkpoint);

        LogPosition from = checkpoint < 0 ? LogPosition.BEGINNING : new LogPosition(checkpoint + 1);
        subscription = sharedLog.subscribe(RougarouTags.toolTasks(), from, entry -> {
            try {
                RougarouEvent event = rougarouLog.decode(entry);
                if (event instanceof ToolRequested req) {
                    if (inFlight.add(req.toolCallId())) {
                        try {
                            handle(req);
                            saveCheckpoint(entry.seqnum());
                        } finally {
                            inFlight.remove(req.toolCallId());
                        }
                    }
                }
            } catch (Throwable t) {
                log.error("tool worker {} failed handling entry seqnum={}", config.workerId(), entry.seqnum(), t);
            }
        });
    }

    private void handle(ToolRequested req) {
        // Cross-restart idempotency: skip if the tool already produced a terminal completion
        // (success or non-retryable failure) for this toolCallId on the session log. Prevents
        // a duplicate side effect when a previous saveCheckpoint failed silently and the
        // worker is replaying ToolRequested events.
        if (alreadyTerminallyHandled(req)) {
            log.info("tool worker {} skipping toolCallId={} — already has a terminal completion on session tag",
                    config.workerId(), req.toolCallId());
            return;
        }

        Optional<Tool> maybeTool = config.toolRegistry().find(req.toolName());
        if (maybeTool.isEmpty()) {
            rougarouLog.append(new ToolFailed(
                    req.sessionId(), req.requestId(), req.toolCallId(), req.toolName(),
                    "ToolNotFound",
                    "no tool registered with name: " + req.toolName(),
                    1, false, Instant.now()
            )).join();
            return;
        }
        Tool tool = maybeTool.get();

        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                String result = tool.invoke(req.argsJson());
                rougarouLog.append(new ToolCompleted(
                        req.sessionId(), req.requestId(), req.toolCallId(), req.toolName(),
                        result, Instant.now()
                )).join();
                return;
            } catch (ToolException e) {
                boolean willRetry = e.isRetryable() && attempt < config.maxAttempts();
                rougarouLog.append(new ToolFailed(
                        req.sessionId(), req.requestId(), req.toolCallId(), req.toolName(),
                        e.getClass().getSimpleName(), e.getMessage(),
                        attempt, willRetry, Instant.now()
                )).join();
                if (!willRetry) return;
                sleepBackoff(attempt);
            } catch (RuntimeException e) {
                rougarouLog.append(new ToolFailed(
                        req.sessionId(), req.requestId(), req.toolCallId(), req.toolName(),
                        e.getClass().getSimpleName(), e.getMessage(),
                        attempt, false, Instant.now()
                )).join();
                return;
            }
        }
    }

    /**
     * True if the session log already contains a terminal completion or a non-retryable failure
     * for this tool call id. Read scope is bounded by the session tag (per-session, not the
     * full tool-task queue), so cost scales with one session's history.
     */
    private boolean alreadyTerminallyHandled(ToolRequested req) {
        try {
            return rougarouLog.readSession(req.sessionId()).join().stream().anyMatch(e ->
                    (e instanceof ToolCompleted tc && req.toolCallId().equals(tc.toolCallId()))
                            || (e instanceof ToolFailed tf
                                    && req.toolCallId().equals(tf.toolCallId())
                                    && !tf.retryable()));
        } catch (Exception e) {
            // If the lookup fails fall through and re-process — better to risk a duplicate than
            // to silently drop a request because the read transiently failed.
            log.warn("tool worker {} failed reading session tag for idempotency check (toolCallId={})",
                    config.workerId(), req.toolCallId(), e);
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
            byte[] data = sharedLog.getView(RougarouTags.toolTasks())
                    .getValue("checkpoint:" + config.workerId()).join();
            if (data == null || data.length < 8) return -1L;
            return ByteBuffer.wrap(data).getLong();
        } catch (Exception e) {
            return -1L;
        }
    }

    private void saveCheckpoint(long seqnum) {
        try {
            sharedLog.getView(RougarouTags.toolTasks())
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
