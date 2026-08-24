package com.minigame.platform.game.application;

import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.room.domain.RoomId;

public interface GameSchedulePort {
    Cancellation schedule(RoomId roomId, GameDeadline deadline, Runnable callback);

    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }
}
