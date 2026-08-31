package com.minigame.platform.game.application;

import com.minigame.platform.game.domain.liar.LiarWord;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LiarContentPort {
    boolean available(String categoryCode, Set<UUID> excludedIds, int required);

    List<LiarWord> select(String categoryCode, Set<UUID> excludedIds, int limit);
}
