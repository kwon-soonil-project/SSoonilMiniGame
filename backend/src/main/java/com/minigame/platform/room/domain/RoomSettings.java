package com.minigame.platform.room.domain;

import java.util.Objects;

public record RoomSettings(
    GameType gameType,
    int maxParticipants,
    int rounds,
    int actionSeconds,
    int discussionSeconds,
    String categoryPack
) {
    public RoomSettings {
        Objects.requireNonNull(gameType, "gameType");
        if (maxParticipants < gameType.minimumParticipants()
            || maxParticipants > gameType.maximumParticipants()) {
            throw new RoomRuleViolation("ROOM_MAX_PLAYERS_OUT_OF_RANGE");
        }
        if (rounds < 1 || actionSeconds < 1 || discussionSeconds < 1) {
            throw new RoomRuleViolation("ROOM_SETTINGS_INVALID");
        }
        if (categoryPack == null || categoryPack.isBlank()) {
            throw new RoomRuleViolation("ROOM_CATEGORY_PACK_REQUIRED");
        }
        categoryPack = categoryPack.strip();
    }
}
