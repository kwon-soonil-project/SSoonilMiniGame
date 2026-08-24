package com.minigame.platform.game.application;

import com.minigame.platform.room.domain.GameType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GameSessionPort {
    UUID start(StartGameSession command);

    void complete(UUID sessionId, List<GameParticipantResult> results, Instant endedAt);

    boolean interrupt(UUID sessionId, Instant interruptedAt);

    int interruptRunning(Instant interruptedAt);

    record StartGameSession(
            UUID sessionId,
            UUID roomId,
            GameType gameType,
            String settingsJson,
            Instant startedAt
    ) {
    }

    record GameParticipantResult(
            UUID actorId,
            String nickname,
            int score,
            int rank,
            int roundsPlayed
    ) {
    }
}
