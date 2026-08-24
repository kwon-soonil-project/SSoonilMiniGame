package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.room.domain.GameType;

import java.time.Instant;
import java.util.List;

public interface GameModule {
    GameType type();

    GameTransition start(GameStartContext context);

    GameTransition handle(GameState state, ActorId actorId, GameAction action, Instant now);

    GameTransition expire(GameState state, GameDeadline expected, Instant now);

    GameTransition removePlayer(GameState state, ActorId actorId, Instant now);

    GameTransition synchronizePlayers(GameState state, List<GamePlayer> players, Instant now);

    GameProjection project(GameState state, ActorId viewer);
}
