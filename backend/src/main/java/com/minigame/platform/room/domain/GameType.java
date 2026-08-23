package com.minigame.platform.room.domain;

public enum GameType {
    LIAR(4, 10),
    DRAWING(2, 10),
    CHOSUNG(1, 12),
    MAJORITY(3, 12);

    private final int minimumParticipants;
    private final int maximumParticipants;

    GameType(int minimumParticipants, int maximumParticipants) {
        this.minimumParticipants = minimumParticipants;
        this.maximumParticipants = maximumParticipants;
    }

    public int minimumParticipants() {
        return minimumParticipants;
    }

    public int maximumParticipants() {
        return maximumParticipants;
    }
}
