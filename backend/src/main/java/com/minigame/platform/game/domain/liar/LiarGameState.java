package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record LiarGameState(
        UUID sessionId,
        int round,
        int phaseVersion,
        int totalRounds,
        int actionSeconds,
        int discussionSeconds,
        LiarPhase phase,
        Instant deadlineAt,
        List<GamePlayer> players,
        List<LiarWord> words,
        int wordIndex,
        List<ActorId> liarBag,
        long randomState,
        ActorId liarId,
        List<ActorId> hintOrder,
        int hintIndex,
        Map<ActorId, String> hints,
        Set<ActorId> discussionEndVotes,
        Set<ActorId> discussionEndRespondents,
        Map<ActorId, ActorId> votes,
        Set<ActorId> revoteCandidates,
        boolean liarGuessSubmitted,
        RoundResult roundResult
) implements GameState {
    public LiarGameState {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (round < 1 || phaseVersion < 1 || totalRounds < round || actionSeconds < 1 || discussionSeconds < 1 || wordIndex < 0 || hintIndex < 0) {
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
        discussionEndVotes = Set.copyOf(Objects.requireNonNull(discussionEndVotes, "discussionEndVotes"));
        discussionEndRespondents = Set.copyOf(Objects.requireNonNull(discussionEndRespondents, "discussionEndRespondents"));
        votes = Map.copyOf(Objects.requireNonNull(votes, "votes"));
        revoteCandidates = Set.copyOf(Objects.requireNonNull(revoteCandidates, "revoteCandidates"));
    }

    public LiarWord word() {
        return words.get(wordIndex);
    }

    public ActorId currentHinter() {
        return phase == LiarPhase.HINTING && hintIndex < hintOrder.size() ? hintOrder.get(hintIndex) : null;
    }

    public record RoundResult(String winner, boolean invalidated, ActorId accusedId, boolean liarGuessedCorrectly) {
        public RoundResult {
            if (!"LIAR".equals(winner) && !"CITIZENS".equals(winner) && !"INVALIDATED".equals(winner)) {
                throw new IllegalArgumentException("winner");
            }
            if (invalidated != "INVALIDATED".equals(winner)) {
                throw new IllegalArgumentException("invalidated");
            }
        }

        public static RoundResult liarSurvived() {
            return new RoundResult("LIAR", false, null, false);
        }

        public static RoundResult citizensWon(ActorId accusedId, boolean liarGuessedCorrectly) {
            return new RoundResult("CITIZENS", false, Objects.requireNonNull(accusedId, "accusedId"), liarGuessedCorrectly);
        }

        public static RoundResult invalidatedRound() {
            return new RoundResult("INVALIDATED", true, null, false);
        }
    }
}
