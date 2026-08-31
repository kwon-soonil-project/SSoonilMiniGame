package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GameTransition(
        GameState state,
        List<GameSignal> signals,
        Map<ActorId, Integer> scoreDeltas,
        Optional<GameDeadline> deadline,
        boolean completed
) {
    public GameTransition {
        state = Objects.requireNonNull(state, "state");
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        scoreDeltas = Map.copyOf(Objects.requireNonNull(scoreDeltas, "scoreDeltas"));
        deadline = Objects.requireNonNull(deadline, "deadline");
    }
}
