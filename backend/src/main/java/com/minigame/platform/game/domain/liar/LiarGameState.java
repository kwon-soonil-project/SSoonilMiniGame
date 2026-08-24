package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LiarGameState(
        UUID sessionId,
        int round,
        int phaseVersion,
        int actionSeconds,
        int discussionSeconds,
        LiarPhase phase,
        Instant deadlineAt,
        List<GamePlayer> players,
        List<LiarWord> words,
        int wordIndex,
        List<ActorId> liarBag,
        ActorId liarId,
        List<ActorId> hintOrder,
        int hintIndex,
        Map<ActorId, String> hints
) implements GameState {
    public LiarGameState {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (round < 1 || phaseVersion < 1 || actionSeconds < 1 || discussionSeconds < 1 || wordIndex < 0 || hintIndex < 0) {
            throw new IllegalArgumentException("round state");
        }
        phase = Objects.requireNonNull(phase, "phase");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        words = List.copyOf(Objects.requireNonNull(words, "words"));
        if (wordIndex >= words.size()) {
            throw new IllegalArgumentException("wordIndex");
        }
        liarBag = List.copyOf(Objects.requireNonNull(liarBag, "liarBag"));
        liarId = Objects.requireNonNull(liarId, "liarId");
        hintOrder = List.copyOf(Objects.requireNonNull(hintOrder, "hintOrder"));
        if (hintIndex > hintOrder.size()) {
            throw new IllegalArgumentException("hintIndex");
        }
        hints = Map.copyOf(Objects.requireNonNull(hints, "hints"));
    }

    public LiarWord word() {
        return words.get(wordIndex);
    }

    public ActorId currentHinter() {
        return phase == LiarPhase.HINTING && hintIndex < hintOrder.size() ? hintOrder.get(hintIndex) : null;
    }
}
