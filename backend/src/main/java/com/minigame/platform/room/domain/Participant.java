package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.Objects;

public record Participant(
    ActorId actorId,
    String nickname,
    boolean ready,
    boolean spectator,
    long joinedOrder
) {
    public Participant {
        Objects.requireNonNull(actorId, "actorId");
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("nickname");
        }
        nickname = nickname.strip();
        if (joinedOrder < 0) {
            throw new IllegalArgumentException("joinedOrder");
        }
        if (spectator && ready) {
            throw new RoomRuleViolation("ROOM_SPECTATOR_CANNOT_READY");
        }
    }

    public Participant withReady(boolean nextReady) {
        return new Participant(actorId, nickname, nextReady, spectator, joinedOrder);
    }

    public Participant withSpectator(boolean nextSpectator) {
        return new Participant(actorId, nickname, false, nextSpectator, joinedOrder);
    }
}
