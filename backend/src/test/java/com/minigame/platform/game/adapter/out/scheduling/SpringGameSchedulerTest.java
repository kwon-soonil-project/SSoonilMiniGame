package com.minigame.platform.game.adapter.out.scheduling;

import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.room.domain.RoomId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SpringGameSchedulerTest {
    private static final RoomId ROOM_ID = new RoomId(UUID.fromString("00000000-0000-0000-0000-000000003101"));
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000003102");

    @Test
    void schedules_a_future_deadline_at_its_absolute_instant() {
        var scheduler = new RecordingTaskScheduler();
        var deadline = new GameDeadline(SESSION_ID, 1, 1, Instant.parse("2026-08-24T00:00:30Z"));
        var gameScheduler = new SpringGameScheduler(
                scheduler, Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC)
        );

        gameScheduler.schedule(ROOM_ID, deadline, () -> { });

        assertThat(scheduler.scheduledAt()).containsExactly(deadline.at());
        assertThat(scheduler.delays()).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void schedules_a_past_deadline_at_the_injected_clock_instant() {
        var scheduler = new RecordingTaskScheduler();
        var now = Instant.parse("2026-08-24T00:00:30Z");
        var deadline = new GameDeadline(SESSION_ID, 1, 1, now.minusSeconds(1));
        var gameScheduler = new SpringGameScheduler(scheduler, Clock.fixed(now, ZoneOffset.UTC));

        gameScheduler.schedule(ROOM_ID, deadline, () -> { });

        assertThat(scheduler.scheduledAt()).containsExactly(now);
    }

    @Test
    void cancelling_a_replaced_deadline_prevents_its_callback() {
        var scheduler = new RecordingTaskScheduler();
        var callbacks = new ArrayList<String>();
        var gameScheduler = new SpringGameScheduler(
                scheduler, Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC)
        );

        var obsolete = gameScheduler.schedule(ROOM_ID, new GameDeadline(SESSION_ID, 1, 1, Instant.parse("2026-08-24T00:00:10Z")),
                () -> callbacks.add("obsolete"));
        obsolete.cancel();
        gameScheduler.schedule(ROOM_ID, new GameDeadline(SESSION_ID, 1, 2, Instant.parse("2026-08-24T00:00:20Z")),
                () -> callbacks.add("replacement"));

        scheduler.runScheduledCallbacks();

        assertThat(callbacks).containsExactly("replacement");
    }

    @Test
    void callback_failure_is_contained_and_the_same_deadline_retries_until_cancelled() {
        var scheduler = new RecordingTaskScheduler();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var deadline = new GameDeadline(SESSION_ID, 1, 1, Instant.parse("2026-08-24T00:00:10Z"));
        var gameScheduler = new SpringGameScheduler(
                scheduler, Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC)
        );

        var cancellation = gameScheduler.schedule(ROOM_ID, deadline, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient expiry failure");
            }
        });

        assertThatCode(scheduler::runScheduledCallbacks).doesNotThrowAnyException();
        assertThatCode(scheduler::runScheduledCallbacks).doesNotThrowAnyException();
        assertThat(attempts).hasValue(2);
        cancellation.cancel();
        scheduler.runScheduledCallbacks();
        assertThat(attempts).hasValue(2);
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {
        private final List<ScheduledTask> scheduled = new ArrayList<>();

        List<Instant> scheduledAt() {
            return scheduled.stream().map(ScheduledTask::at).toList();
        }

        List<Duration> delays() {
            return scheduled.stream().map(ScheduledTask::delay).toList();
        }

        void runScheduledCallbacks() {
            scheduled.stream()
                    .filter(task -> !task.future().isCancelled())
                    .forEach(task -> task.callback().run());
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            var future = new RecordingFuture();
            scheduled.add(new ScheduledTask(startTime, delay, task, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException();
        }
    }

    private record ScheduledTask(Instant at, Duration delay, Runnable callback, RecordingFuture future) {
    }

    private static final class RecordingFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
