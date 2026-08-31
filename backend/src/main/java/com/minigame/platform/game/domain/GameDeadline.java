package com.minigame.platform.game.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GameDeadline(UUID sessionId, int round, int phaseVersion, Instant at) {
    public GameDeadline {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (round < 1) {
            throw new IllegalArgumentException("round");
        }
        if (phaseVersion < 1) {
            throw new IllegalArgumentException("phaseVersion");
        }
        at = Objects.requireNonNull(at, "at");
    }

    public boolean matches(UUID sessionId, int round, int phaseVersion) {
        return this.sessionId.equals(sessionId)
                && this.round == round
                && this.phaseVersion == phaseVersion;
    }
}
