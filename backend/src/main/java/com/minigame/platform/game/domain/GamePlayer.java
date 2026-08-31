package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.Objects;

public record GamePlayer(ActorId actorId, String nickname) {
    public GamePlayer {
        actorId = Objects.requireNonNull(actorId, "actorId");
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("nickname");
        }
        nickname = nickname.strip();
    }
}
