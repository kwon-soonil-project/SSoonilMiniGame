package com.minigame.platform.game.domain;

public record GameSettings(int rounds, int actionSeconds, int discussionSeconds, String categoryPack) {
    public GameSettings {
        if (rounds < 1 || rounds > 5) {
            throw new IllegalArgumentException("rounds");
        }
        if (actionSeconds < 15 || actionSeconds > 45) {
            throw new IllegalArgumentException("actionSeconds");
        }
        if (discussionSeconds < 60 || discussionSeconds > 180) {
            throw new IllegalArgumentException("discussionSeconds");
        }
        if (categoryPack == null || categoryPack.isBlank()) {
            throw new IllegalArgumentException("categoryPack");
        }
        categoryPack = categoryPack.strip();
    }
}
