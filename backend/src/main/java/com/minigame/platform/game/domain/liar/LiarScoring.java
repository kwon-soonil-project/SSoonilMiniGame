package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the outcome-to-score rule independent from phase transitions. */
public final class LiarScoring {
    private LiarScoring() {
    }

    public static Map<ActorId, Integer> score(LiarGameState state, LiarGameState.RoundResult result) {
        if (result.invalidated()) {
            return Map.of();
        }
        if ("LIAR".equals(result.winner())) {
            return Map.of(state.liarId(), 3);
        }
        if (result.liarGuessedCorrectly()) {
            return Map.of(state.liarId(), 2);
        }
        var scores = new LinkedHashMap<ActorId, Integer>();
        state.players().stream()
                .map(player -> player.actorId())
                .filter(playerId -> !playerId.equals(state.liarId()))
                .forEach(playerId -> scores.put(playerId, 1));
        return Map.copyOf(scores);
    }
}
