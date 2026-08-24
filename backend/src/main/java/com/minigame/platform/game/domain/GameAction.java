package com.minigame.platform.game.domain;

import java.util.Map;
import java.util.Objects;

public record GameAction(String type, Map<String, Object> data) {
    public GameAction {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("action type");
        }
        type = type.strip();
        data = Map.copyOf(Objects.requireNonNull(data, "data"));
    }
}
