package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GameSignal(String type, Map<String, Object> payload, Optional<ActorId> recipient) {
    public GameSignal {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("signal type");
        }
        type = type.strip();
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        recipient = Objects.requireNonNull(recipient, "recipient");
    }

    public static GameSignal publicSignal(String type, Map<String, Object> payload) {
        return new GameSignal(type, payload, Optional.empty());
    }

    public static GameSignal privateSignal(ActorId recipient, String type, Map<String, Object> payload) {
        return new GameSignal(type, payload, Optional.of(Objects.requireNonNull(recipient, "recipient")));
    }
}
