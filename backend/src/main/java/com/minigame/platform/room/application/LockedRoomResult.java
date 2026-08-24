package com.minigame.platform.room.application;

import com.minigame.platform.room.domain.Room;

import java.util.Objects;

public record LockedRoomResult<T>(T value, Room.Snapshot snapshot) {
    public LockedRoomResult {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
