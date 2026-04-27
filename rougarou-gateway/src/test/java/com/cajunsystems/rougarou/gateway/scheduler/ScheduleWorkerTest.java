package com.cajunsystems.rougarou.gateway.scheduler;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;
import com.cajunsystems.rougarou.core.RougarouLog;
import com.cajunsystems.rougarou.core.RougarouTags;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.ScheduleFired;
import com.cajunsystems.rougarou.core.events.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ScheduleWorkerTest {

    @Test
    void firesAfterRequestedDelay() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(log);
            try (ScheduleWorker worker = new ScheduleWorker(rl)) {
                worker.start();

                Scheduler scheduler = new Scheduler(rl);
                String sid = "ses-test";
                Instant before = Instant.now();
                scheduler.scheduleUserInputAfter(sid, Duration.ofMillis(200), "ping").get();

                // After ~200ms a UserMessage should appear on the session tag.
                await().atMost(2, TimeUnit.SECONDS).until(() -> {
                    List<RougarouEvent> events = rl.readSession(sid).join();
                    return events.stream().anyMatch(e -> e instanceof UserMessage um
                            && um.content().equals("ping"));
                });

                // The fire should not have happened materially before the requested time.
                List<RougarouEvent> events = rl.readSession(sid).join();
                ScheduleFired fired = (ScheduleFired) events.stream()
                        .filter(e -> e instanceof ScheduleFired).findFirst().orElseThrow();
                assertThat(fired.firedAt()).isAfterOrEqualTo(before.plusMillis(150));
            }
        }
    }

    @Test
    void cancelledScheduleDoesNotFire() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(log);
            try (ScheduleWorker worker = new ScheduleWorker(rl)) {
                worker.start();

                Scheduler scheduler = new Scheduler(rl);
                String sid = "ses-cancel";
                String scheduleId = scheduler.scheduleUserInputAfter(sid, Duration.ofMillis(400), "should not fire").get();
                Thread.sleep(50);
                scheduler.cancel(sid, scheduleId, "test").get();

                Thread.sleep(800);

                List<RougarouEvent> events = rl.readSession(sid).join();
                assertThat(events).noneMatch(e -> e instanceof ScheduleFired);
                assertThat(events).noneMatch(e -> e instanceof UserMessage um && um.content().equals("should not fire"));
            }
        }
    }

    @Test
    void replayOnStartupSkipsAlreadyFired() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(log);
            String sid = "ses-replay";

            // Fire it once with a worker.
            try (ScheduleWorker worker = new ScheduleWorker(rl)) {
                worker.start();
                new Scheduler(rl).scheduleUserInputAfter(sid, Duration.ofMillis(100), "once").get();
                await().atMost(2, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).join().stream().anyMatch(e -> e instanceof ScheduleFired));
            }

            long firedBefore = rl.readSession(sid).join().stream()
                    .filter(e -> e instanceof ScheduleFired).count();

            // Now restart a fresh worker and confirm it doesn't re-fire the same schedule.
            try (ScheduleWorker worker2 = new ScheduleWorker(rl)) {
                worker2.start();
                Thread.sleep(400);
            }

            long firedAfter = rl.readSession(sid).join().stream()
                    .filter(e -> e instanceof ScheduleFired).count();

            assertThat(firedAfter).isEqualTo(firedBefore);
            assertThat(firedAfter).isEqualTo(1);
        }
    }

    @Test
    void racingPeerWorkersProduceAtMostOneEffectiveUserMessage() throws Exception {
        // Simulates the multi-process race: two peer ScheduleWorkers racing to fire the same
        // schedule both append a UserMessage. Because the messageId is derived from the
        // scheduleId, both writes carry the same messageId and SessionState dedupes the second
        // one — the conversation only counts the user turn once.
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(log);

            // Pre-populate the log with the SessionCreated event so the SessionActor doesn't
            // emit one of its own.
            String sid = "ses-race";
            rl.append(new com.cajunsystems.rougarou.core.events.SessionCreated(
                    sid, "agent", "", java.util.Map.of(),
                    java.time.Instant.now())).get();

            try (ScheduleWorker w1 = new ScheduleWorker(rl);
                 ScheduleWorker w2 = new ScheduleWorker(rl)) {
                w1.start();
                w2.start();

                // Both workers will see the ScheduleRequested and race to fire.
                new Scheduler(rl).scheduleUserInputAfter(sid,
                        java.time.Duration.ofMillis(80), "race payload").get();

                // Wait long enough for both workers to have processed the firing window.
                await().atMost(3, TimeUnit.SECONDS).until(() ->
                        rl.readSession(sid).get().stream()
                                .anyMatch(e -> e instanceof com.cajunsystems.rougarou.core.events.UserMessage));
                Thread.sleep(400);

                List<com.cajunsystems.rougarou.core.events.UserMessage> userMessages =
                        rl.readSession(sid).get().stream()
                                .filter(e -> e instanceof com.cajunsystems.rougarou.core.events.UserMessage)
                                .map(e -> (com.cajunsystems.rougarou.core.events.UserMessage) e)
                                .toList();

                // Two physical UserMessage events may exist (we don't guarantee single-write at
                // the log level), but they MUST share the same messageId so the session actor
                // dedupes them.
                long distinctIds = userMessages.stream()
                        .map(com.cajunsystems.rougarou.core.events.UserMessage::messageId)
                        .distinct().count();
                assertThat(distinctIds)
                        .as("racing fires must produce a single deterministic messageId")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void scheduleFiredIsRoutedToScheduleAndSessionTags() throws Exception {
        try (var log = SharedLogService.open(SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter()).build())) {

            RougarouLog rl = new RougarouLog(log);
            try (ScheduleWorker worker = new ScheduleWorker(rl)) {
                worker.start();
                new Scheduler(rl).scheduleUserInputAfter("ses-tag", Duration.ofMillis(50), "p").get();

                await().atMost(2, TimeUnit.SECONDS).until(() ->
                        log.readAll(RougarouTags.schedule()).join().size() >= 2); // requested + fired

                long firedOnSession = rl.readSession("ses-tag").join().stream()
                        .filter(e -> e instanceof ScheduleFired).count();
                long firedOnSchedule = log.readAll(RougarouTags.schedule()).join().stream()
                        .map(rl::decode)
                        .filter(e -> e instanceof ScheduleFired).count();

                assertThat(firedOnSession).isEqualTo(1);
                assertThat(firedOnSchedule).isEqualTo(1);
            }
        }
    }
}
