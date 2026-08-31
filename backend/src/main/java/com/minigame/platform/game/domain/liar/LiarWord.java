package com.minigame.platform.game.domain.liar;

import com.minigame.platform.game.domain.GameContent;

import java.util.UUID;
import java.util.Set;
import java.util.Objects;

public record LiarWord(UUID id, String categoryCode, String answer, Set<String> aliases) implements GameContent {
    public LiarWord {
        id = Objects.requireNonNull(id, "id");
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new IllegalArgumentException("categoryCode");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer");
        }
        categoryCode = categoryCode.strip();
        answer = answer.strip();
        aliases = Set.copyOf(Objects.requireNonNull(aliases, "aliases"));
    }
}
