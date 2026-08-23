package com.minigame.platform.room.adapter.in.realtime;

import java.util.Map;

public final class RoomCommands {
    private RoomCommands() {
    }

    public record RoomCommand(String requestId, String type, Map<String, Object> payload) {
        public RoomCommand {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type");
            }
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
