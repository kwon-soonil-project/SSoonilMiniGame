package com.minigame.platform.room.domain;

public record RoomCode(String value) {
    public RoomCode {
        if (value == null || !value.matches("\\d{6}")) {
            throw new IllegalArgumentException("roomCode");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
