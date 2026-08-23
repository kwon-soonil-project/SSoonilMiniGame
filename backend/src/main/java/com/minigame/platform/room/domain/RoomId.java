package com.minigame.platform.room.domain;

import java.util.Objects;
import java.util.UUID;

public record RoomId(UUID value) {
    public RoomId {
        Objects.requireNonNull(value, "roomId");
    }

    public static RoomId random() {
        return new RoomId(UUID.randomUUID());
    }
}
