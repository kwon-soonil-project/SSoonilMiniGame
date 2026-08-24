package com.minigame.platform.game.adapter.out.scheduling;

import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.room.domain.RoomId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SpringGameSchedulerTest {
    private static final RoomId ROOM_ID = new RoomId(UUID.fromString("00000000-0000-0000-0000-000000003101"));
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000003102");

    @Test
    void runs_callback_at_the_deadline() throws InterruptedException {
        var scheduler = scheduler();
        try {
            var callbacks = new CountDownLatch(1);
            var gameScheduler = new SpringGameScheduler(scheduler, Clock.systemUTC());

            gameScheduler.schedule(ROOM_ID, deadlineAfterMillis(100), callbacks::countDown);

            assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void cancelling_a_replaced_deadline_prevents_its_callback() throws InterruptedException {
        var scheduler = scheduler();
        try {
            var callbacks = new AtomicInteger();
            var replacementRan = new CountDownLatch(1);
            var gameScheduler = new SpringGameScheduler(scheduler, Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));

            var obsolete = gameScheduler.schedule(ROOM_ID, deadlineAfterMillis(250), callbacks::incrementAndGet);
            obsolete.cancel();
            gameScheduler.schedule(ROOM_ID, deadlineAfterMillis(50), () -> {
                callbacks.incrementAndGet();
                replacementRan.countDown();
            });

            assertThat(replacementRan.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertThat(callbacks).hasValue(1);
        } finally {
            scheduler.shutdown();
        }
    }

    private static ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    private static GameDeadline deadlineAfterMillis(long delayMillis) {
        return new GameDeadline(SESSION_ID, 1, 1, Instant.now().plusMillis(delayMillis));
    }
}
