package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameSettings;
import com.minigame.platform.game.domain.GameStartContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiarDepartureTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final LiarGameModule module = new LiarGameModule();

    @Test
    void liar_departure_invalidates_round_without_scores() {
        var state = (LiarGameState) module.start(context(List.of("a", "b", "c", "d"))).state();

        var result = module.removePlayer(state, state.liarId(), NOW);

        assertThat(((LiarGameState) result.state()).phase()).isEqualTo(LiarPhase.ROUND_RESULT);
        assertThat(result.scoreDeltas()).isEmpty();
    }

    @Test
    void roster_below_four_invalidates_round_without_scores() {
        var state = (LiarGameState) module.start(context(List.of("a", "b", "c", "d"))).state();

        var result = module.removePlayer(state, state.players().getFirst().actorId(), NOW);

        assertThat(((LiarGameState) result.state()).phase()).isEqualTo(LiarPhase.ROUND_RESULT);
        assertThat(result.scoreDeltas()).isEmpty();
    }

    @Test
    void synchronize_players_adds_joiner_for_the_next_round() {
        var state = (LiarGameState) module.start(context(List.of("a", "b", "c", "d"))).state();
        var invalidated = module.removePlayer(state, state.liarId(), NOW);
        var players = ((LiarGameState) invalidated.state()).players().stream().map(GamePlayer::actorId)
                .map(ActorId::value).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        players.add("new");
        var synchronizedPlayers = players.stream().map(id -> new GamePlayer(new ActorId(id), id)).toList();

        var result = module.synchronizePlayers(invalidated.state(), synchronizedPlayers, NOW);

        assertThat(((LiarGameState) result.state()).players()).extracting(GamePlayer::actorId).contains(new ActorId("new"));
    }

    private static GameStartContext context(List<String> ids) {
        return new GameStartContext(UUID.fromString("00000000-0000-0000-0000-000000005101"),
                ids.stream().map(id -> new GamePlayer(new ActorId(id), id)).toList(), new GameSettings(2, 20, 60, "all"),
                List.of(new LiarWord(UUID.randomUUID(), "food", "붕어빵", Set.of("fish bread")),
                        new LiarWord(UUID.randomUUID(), "animal", "호랑이", Set.of("tiger"))), NOW, new Random(11));
    }
}
