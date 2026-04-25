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
