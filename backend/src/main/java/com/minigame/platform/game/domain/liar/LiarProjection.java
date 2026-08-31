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
            List<PublicHintStatus> hintStatuses,
            Set<ActorId> submittedPlayerIds,
            Set<ActorId> revoteCandidates,
            ActorId liarId,
            String answer,
            LiarGameState.RoundResult roundResult
    ) implements GameProjection.View {
        public PublicState {
            hints = List.copyOf(hints);
            hintStatuses = List.copyOf(hintStatuses);
            submittedPlayerIds = Set.copyOf(submittedPlayerIds);
            revoteCandidates = Set.copyOf(revoteCandidates);
        }
    }

    public record PublicHint(ActorId playerId, String text) {
    }

    public record PublicHintStatus(ActorId playerId, String status) {
        public PublicHintStatus {
            if (!"SUBMITTED".equals(status) && !"SKIPPED".equals(status)) {
                throw new IllegalArgumentException("hint status");
            }
        }
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
