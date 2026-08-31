package com.minigame.platform.game.application;

import com.minigame.platform.game.domain.GameModule;
import com.minigame.platform.room.domain.GameType;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class GameModuleRegistry {
    private final Map<GameType, GameModule> modules;

    public GameModuleRegistry(Collection<? extends GameModule> modules) {
        var registered = new EnumMap<GameType, GameModule>(GameType.class);
        for (var module : Objects.requireNonNull(modules, "modules")) {
            var nonNullModule = Objects.requireNonNull(module, "module");
            var duplicate = registered.putIfAbsent(nonNullModule.type(), nonNullModule);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate game module for " + nonNullModule.type());
            }
        }
        this.modules = Map.copyOf(registered);
    }

    public GameModule get(GameType gameType) {
        return find(gameType).orElseThrow(() -> new IllegalArgumentException("Game module is not registered for " + gameType));
    }

    public Optional<GameModule> find(GameType gameType) {
        return Optional.ofNullable(modules.get(Objects.requireNonNull(gameType, "gameType")));
    }
}
