package com.minigame.platform.game.domain;

import java.util.Objects;
import java.util.Optional;

public record GameProjection(View publicState, Optional<View> privateState) {
    public GameProjection {
        publicState = Objects.requireNonNull(publicState, "publicState");
        privateState = Objects.requireNonNull(privateState, "privateState");
    }

    public interface View {
    }
}
