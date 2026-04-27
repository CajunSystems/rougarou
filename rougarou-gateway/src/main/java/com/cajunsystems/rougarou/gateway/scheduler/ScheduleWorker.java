package com.cajunsystems.rougarou.gateway.scheduler;

import com.cajunsystems.gumbo.api.SharedLog;
import com.cajunsystems.gumbo.core.LogEntry;
import com.cajunsystems.gumbo.core.LogPosition;
import com.cajunsystems.rougarou.core.Ids;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.RougarouTags;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.ScheduleCancelled;
import com.cajunsystems.rougarou.core.events.ScheduleFired;
import com.cajunsystems.rougarou.core.events.ScheduleKind;
import com.cajunsystems.rougarou.core.events.ScheduleRequested;
import com.cajunsystems.rougarou.core.events.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The scheduler worker.
 *
 * <p>Subscribes to {@code rougarou.schedule} and maintains an in-memory priority queue of pending
 * fires keyed by {@code fireAt}. A dedicated virtual thread parks on the next due entry; when it
 * fires it appends a {@link ScheduleFired} event (which lands on the schedule + session tags) and,
 * for {@link ScheduleKind#USER_INPUT}, also a {@link UserMessage} so the receiving session actor
 * picks it up just as if a client had typed it.
 *
 * <h2>Reliability</h2>
 * The schedule tag is the single source of truth. On startup the worker replays the tag from the
 * beginning: every {@link ScheduleRequested} goes into the queue, every {@link ScheduleCancelled}
 * or {@link ScheduleFired} marks an id as finalized so a duplicate never fires. Multiple worker
 * processes can run; each independently rebuilds the queue, but only the first to write
 * {@link ScheduleFired} for a given {@code scheduleId} wins (the others see it on their own
 * subscription and skip it).
 *
 * <p>Note: this is the gumbo-native scheduler. It's intentionally simple — no retries, no recurring
 * schedules. For long-running, multi-step durable workflows reach for boudin instead.
 */
public final class ScheduleWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScheduleWorker.class);

    private final RougarouLog rougarouLog;
    private final SharedLog sharedLog;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition queueChanged = lock.newCondition();
    private final PriorityQueue<Pending> queue = new PriorityQueue<>();
    private final Set<String> finalized = new HashSet<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private SharedLog.Subscription subscription;
    private Thread loopThread;

    public ScheduleWorker(RougarouLog rougarouLog) {
        this.rougarouLog = rougarouLog;
        this.sharedLog = rougarouLog.sharedLog();
    }

    public void start() {
        // Atomic guard so concurrent start() callers can't both launch the loop thread.
        if (!running.compareAndSet(false, true)) return;

        try {
            // Synchronously apply every existing schedule event before the firing loop comes online.
            // This avoids a startup race where ScheduleRequested arrives ahead of the matching
            // ScheduleFired marker (when both already exist in the log) and the loop double-fires.
            List<LogEntry> existing = sharedLog.readAll(RougarouTags.schedule()).join();
            long replayThrough = -1;
            for (LogEntry entry : existing) {
                onEvent(rougarouLog.decode(entry));
                replayThrough = entry.seqnum();
            }

            subscription = sharedLog.subscribe(
                    RougarouTags.schedule(),
                    new LogPosition(replayThrough + 1),
                    entry -> {
                        try {
                            onEvent(rougarouLog.decode(entry));
                        } catch (Throwable t) {
                            log.error("scheduler failed handling entry seqnum={}", entry.seqnum(), t);
                        }
                    });

            loopThread = Thread.ofVirtual().name("rougarou-scheduler").start(this::loop);
        } catch (RuntimeException e) {
            // If startup blew up, release the guard so a retry can succeed.
            running.set(false);
            throw e;
        }
    }

    private void onEvent(RougarouEvent event) {
        lock.lock();
        try {
            switch (event) {
                case ScheduleRequested r -> {
                    if (!finalized.contains(r.scheduleId())) {
                        queue.add(new Pending(r.scheduleId(), r.sessionId(), r.fireAt(), r.kind(), r.payload()));
                        queueChanged.signalAll();
                    }
                }
                case ScheduleCancelled c -> finalized.add(c.scheduleId());
                case ScheduleFired f -> finalized.add(f.scheduleId());
                default -> { /* ignored — not on schedule tag */ }
            }
        } finally {
            lock.unlock();
        }
    }

    private void loop() {
        while (running.get()) {
            Pending due = null;
            lock.lock();
            try {
                while (running.get() && queue.isEmpty()) {
                    queueChanged.await();
                }
                if (!running.get()) return;

                Pending head = queue.peek();
                long nanos = Duration.between(Instant.now(), head.fireAt()).toNanos();
                if (nanos > 0) {
                    // Park until either the head is due or a sooner entry arrives.
                    queueChanged.awaitNanos(nanos);
                    continue;
                }
                Pending candidate = queue.poll();
                if (candidate != null && !finalized.contains(candidate.scheduleId())) {
                    due = candidate;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Append outside the lock — the subscription callback wants it too.
            if (due != null) {
                fire(due);
            }
        }
    }

    private void fire(Pending due) {
        Instant now = Instant.now();
        try {
            // Mark fired first. ScheduleFired lands on both schedule and session tags so any
            // peer scheduler observes it and won't fire a duplicate.
            rougarouLog.append(new ScheduleFired(
                    due.sessionId(), due.scheduleId(), due.kind(), due.payload(), now, now
            )).join();

            // Then deliver the actual payload according to its kind.
            switch (due.kind()) {
                case USER_INPUT -> rougarouLog.append(new UserMessage(
                        due.sessionId(), Ids.newMessageId(), due.payload(), now)).join();
            }
        } catch (Exception e) {
            log.error("scheduler failed firing schedule {}", due.scheduleId(), e);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        lock.lock();
        try { queueChanged.signalAll(); } finally { lock.unlock(); }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        if (loopThread != null) {
            try { loopThread.join(Duration.ofSeconds(2)); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            loopThread = null;
        }
    }

    private record Pending(String scheduleId, String sessionId, Instant fireAt,
                            ScheduleKind kind, String payload) implements Comparable<Pending> {
        @Override
        public int compareTo(Pending o) {
            int c = fireAt.compareTo(o.fireAt);
            return c != 0 ? c : scheduleId.compareTo(o.scheduleId);
        }
    }
}
