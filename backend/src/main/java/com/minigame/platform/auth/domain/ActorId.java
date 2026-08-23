package com.minigame.platform.auth.domain;

public record ActorId(String value) {
    public ActorId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("actorId");
        }
    }
}
