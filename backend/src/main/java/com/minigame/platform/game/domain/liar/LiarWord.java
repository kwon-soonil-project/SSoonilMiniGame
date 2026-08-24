package com.minigame.platform.game.domain.liar;

import com.minigame.platform.game.domain.GameContent;

import java.util.UUID;
import java.util.Set;

public record LiarWord(UUID id, String categoryCode, String answer, Set<String> aliases) implements GameContent {
    public LiarWord {
        aliases = Set.copyOf(aliases);
    }
}
