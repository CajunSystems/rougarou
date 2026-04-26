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
