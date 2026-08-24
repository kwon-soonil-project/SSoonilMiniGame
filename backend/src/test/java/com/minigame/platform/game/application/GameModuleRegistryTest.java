package com.minigame.platform.game.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameModule;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameProjection;
import com.minigame.platform.game.domain.GameStartContext;
import com.minigame.platform.game.domain.GameState;
import com.minigame.platform.game.domain.GameTransition;
import com.minigame.platform.room.domain.GameType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameModuleRegistryTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000003001");
    private static final Instant DEADLINE = Instant.parse("2026-08-24T00:00:30Z");

    @Test
    void registry_rejects_duplicate_game_type() {
        assertThatThrownBy(() -> new GameModuleRegistry(List.of(new StubModule(GameType.LIAR), new StubModule(GameType.LIAR))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIAR");
    }

    @Test
    void registry_returns_the_module_registered_for_its_game_type() {
        var liarModule = new StubModule(GameType.LIAR);
        var registry = new GameModuleRegistry(List.of(liarModule));

        assertThat(registry.get(GameType.LIAR)).isSameAs(liarModule);
    }

    @Test
    void deadline_matches_only_the_same_session_round_and_phase_version() {
        var expected = new GameDeadline(SESSION_ID, 2, 7, DEADLINE);

        assertThat(expected.matches(SESSION_ID, 2, 7)).isTrue();
        assertThat(expected.matches(SESSION_ID, 2, 8)).isFalse();
        assertThat(expected.matches(UUID.randomUUID(), 2, 7)).isFalse();
        assertThat(expected.matches(SESSION_ID, 3, 7)).isFalse();
    }

    private record StubState() implements GameState {
    }

    private static final class StubModule implements GameModule {
        private final GameType type;

        private StubModule(GameType type) {
            this.type = type;
        }

        @Override
        public GameType type() {
            return type;
        }

        @Override
        public GameTransition start(GameStartContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameTransition handle(GameState state, ActorId actorId, GameAction action, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameTransition expire(GameState state, GameDeadline expected, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameTransition removePlayer(GameState state, ActorId actorId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameTransition synchronizePlayers(GameState state, List<GamePlayer> players, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameProjection project(GameState state, ActorId viewer) {
            throw new UnsupportedOperationException();
        }
    }
}
