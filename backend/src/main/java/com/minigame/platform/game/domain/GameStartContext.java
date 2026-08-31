package com.minigame.platform.game.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

public record GameStartContext(
        UUID sessionId,
        List<GamePlayer> players,
        GameSettings settings,
        List<GameContent> contents,
        Instant now,
        RandomGenerator random
) {
    public GameStartContext {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        settings = Objects.requireNonNull(settings, "settings");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
        if (contents.size() != settings.rounds()) {
            throw new IllegalArgumentException("contents must contain one item per round");
        }
        now = Objects.requireNonNull(now, "now");
        random = Objects.requireNonNull(random, "random");
    }
}
