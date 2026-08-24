package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameProjection;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class LiarProjection {
    private LiarProjection() {
    }

    public record PublicState(
            int round,
            LiarPhase phase,
            Instant deadlineAt,
            ActorId currentHinter,
            List<PublicHint> hints,
            Set<ActorId> submittedPlayerIds,
            LiarGameState.RoundResult roundResult
    ) implements GameProjection.View {
        public PublicState {
            hints = List.copyOf(hints);
            submittedPlayerIds = Set.copyOf(submittedPlayerIds);
        }
    }

    public record PublicHint(ActorId playerId, String text) {
    }

    public record PrivateState(
            String role,
            String category,
            String word,
            boolean hintSubmitted,
            boolean voteSubmitted
    ) implements GameProjection.View {
    }
}
