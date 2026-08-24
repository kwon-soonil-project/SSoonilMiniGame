package com.minigame.platform.game.domain;

public final class GameRuleViolation extends RuntimeException {
    private final String code;

    public GameRuleViolation(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
