package com.minigame.platform.game.application;

import java.util.UUID;

public class GameSessionNotRunningException extends RuntimeException {
    public GameSessionNotRunningException(UUID sessionId) {
        super("Game session is not running: " + sessionId);
    }
}
