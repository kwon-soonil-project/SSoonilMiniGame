package com.minigame.platform.game.adapter.out.scheduling;

import com.minigame.platform.game.application.GameSchedulePort;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.room.domain.RoomId;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SpringGameScheduler implements GameSchedulePort {
    private final TaskScheduler scheduler;
    private final Clock clock;

    public SpringGameScheduler(TaskScheduler scheduler, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Cancellation schedule(RoomId roomId, GameDeadline deadline, Runnable callback) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(callback, "callback");

        var cancelled = new AtomicBoolean();
        var scheduledAt = deadline.at().isBefore(clock.instant()) ? clock.instant() : deadline.at();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!cancelled.get()) {
                callback.run();
            }
        }, scheduledAt);
        if (future == null) {
            throw new IllegalStateException("Game deadline was not scheduled");
        }
        return () -> {
            cancelled.set(true);
            future.cancel(false);
        };
    }
}
