package com.minigame.platform.room.application;

import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomEvent;

import java.util.List;
import java.util.Objects;

public record RoomMutationResult(List<RoomEvent> events, Room.Snapshot snapshot) {
    public RoomMutationResult {
        events = List.copyOf(events);
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
