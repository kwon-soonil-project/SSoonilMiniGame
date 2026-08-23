package com.minigame.platform.room.domain;

public final class RoomRuleViolation extends RuntimeException {
    private final String code;

    public RoomRuleViolation(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
