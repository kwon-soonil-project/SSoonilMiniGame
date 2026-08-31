package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameContent;
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
    void submitted_hint_from_a_departed_citizen_remains_in_order_and_projection() {
        var transition = module.start(context(List.of("a", "b", "c", "d", "e")));
        var state = (LiarGameState) module.expire(
                transition.state(), transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at()
        ).state();
        ActorId departing;
        while (true) {
            var current = state.currentHinter();
            var submitted = module.handle(
                    state, current, new GameAction("HINT_SUBMIT", java.util.Map.of("hint", "기억할 힌트 " + state.hintIndex())), NOW
            );
            state = (LiarGameState) submitted.state();
            if (!current.equals(state.liarId())) {
                departing = current;
                break;
            }
        }
        var nextHinter = state.currentHinter();

        var departed = (LiarGameState) module.removePlayer(state, departing, NOW).state();
        var projection = (LiarProjection.PublicState) module.project(departed, nextHinter).publicState();

        assertThat(departed.players()).extracting(GamePlayer::actorId).doesNotContain(departing);
        assertThat(departed.hintOrder()).contains(departing);
        assertThat(departed.hints()).containsKey(departing);
        assertThat(departed.currentHinter()).isEqualTo(nextHinter);
        assertThat(projection.hints()).extracting(LiarProjection.PublicHint::playerId).contains(departing);
    }

    @Test
    void unsubmitted_future_departure_removes_only_that_turn_without_moving_the_current_index() {
        var transition = module.start(context(List.of("a", "b", "c", "d", "e")));
        var state = (LiarGameState) module.expire(
                transition.state(), transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at()
        ).state();
        var currentHinter = state.currentHinter();
        var future = state.hintOrder().stream()
                .skip(state.hintIndex() + 1L)
                .filter(actorId -> !actorId.equals(state.liarId()))
                .findFirst().orElseThrow();

        var departed = (LiarGameState) module.removePlayer(state, future, NOW).state();

        assertThat(departed.hintOrder()).doesNotContain(future);
        assertThat(departed.hints()).doesNotContainKey(future);
        assertThat(departed.hintIndex()).isEqualTo(state.hintIndex());
        assertThat(departed.currentHinter()).isEqualTo(currentHinter);
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

    @Test
    void departure_during_round_result_preserves_the_completed_outcome_and_deadline() {
        var state = roundResult(context(List.of("a", "b", "c", "d"), 2));

        var result = module.removePlayer(state, state.players().getFirst().actorId(), NOW.plusSeconds(1));

        assertThat(result.state()).isSameAs(state);
        assertThat(result.deadline()).contains(new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt()));
        assertThat(result.scoreDeltas()).isEmpty();
    }

    @Test
    void departure_during_game_result_preserves_the_completed_outcome_and_deadline() {
        var roundResult = roundResult(context(List.of("a", "b", "c", "d"), 1));
        var gameResult = (LiarGameState) module.synchronizePlayers(roundResult, roundResult.players(), NOW).state();

        var result = module.removePlayer(gameResult, gameResult.players().getFirst().actorId(), NOW.plusSeconds(1));

        assertThat(gameResult.phase()).isEqualTo(LiarPhase.GAME_RESULT);
        assertThat(result.state()).isSameAs(gameResult);
        assertThat(result.scoreDeltas()).isEmpty();
    }

    @Test
    void round_result_expiry_requires_synchronized_roster_before_advancing() {
        var state = roundResult(context(List.of("a", "b", "c", "d"), 2));
        var deadline = new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());

        var result = module.expire(state, deadline, deadline.at());

        assertThat(result.state()).isSameAs(state);
    }

    @Test
    void synchronized_roster_below_four_hands_off_to_game_result_without_scoring() {
        var state = roundResult(context(List.of("a", "b", "c", "d"), 2));
        var remaining = state.players().subList(0, 3);

        var result = module.synchronizePlayers(state, remaining, NOW);

        var gameResult = (LiarGameState) result.state();
        assertThat(gameResult.phase()).isEqualTo(LiarPhase.GAME_RESULT);
        assertThat(gameResult.players()).containsExactlyElementsOf(remaining);
        assertThat(result.scoreDeltas()).isEmpty();
    }

    @Test
    void newcomer_is_deferred_from_a_nonempty_liar_bag_then_included_when_it_refills() {
        var state = roundResult(context(List.of("a", "b", "c", "d"), 2));
        var players = new java.util.ArrayList<>(state.players());
        var newcomer = new ActorId("new");
        players.add(new GamePlayer(newcomer, "new"));

        var deferred = (LiarGameState) module.synchronizePlayers(state, players, NOW).state();
        assertThat(deferred.players()).extracting(GamePlayer::actorId).contains(newcomer);
        assertThat(deferred.liarId()).isNotEqualTo(newcomer);
        assertThat(deferred.liarBag()).doesNotContain(newcomer);

        var refillSource = withEmptyBag(state);
        var refilled = (LiarGameState) module.synchronizePlayers(refillSource, players, NOW).state();
        assertThat(refilled.liarId().equals(newcomer) || refilled.liarBag().contains(newcomer)).isTrue();
    }

    private LiarGameState roundResult(GameStartContext context) {
        var transition = module.start(context);
        var state = (LiarGameState) transition.state();
        while (state.phase() != LiarPhase.VOTING) {
            transition = module.expire(state, transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at());
            state = (LiarGameState) transition.state();
        }
        transition = module.expire(state, transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at());
        return (LiarGameState) transition.state();
    }

    private static LiarGameState withEmptyBag(LiarGameState state) {
        return new LiarGameState(state.sessionId(), state.round(), state.phaseVersion(), state.totalRounds(), state.actionSeconds(), state.discussionSeconds(), state.phase(), state.deadlineAt(), state.players(), state.words(), state.wordIndex(), List.of(), state.randomState(), state.liarId(), state.hintOrder(), state.hintIndex(), state.hints(), state.discussionEndVotes(), state.discussionEndRespondents(), state.votes(), state.revoteCandidates(), state.liarGuessSubmitted(), state.roundResult());
    }

    private static GameStartContext context(List<String> ids) {
        return context(ids, 2);
    }

    private static GameStartContext context(List<String> ids, int rounds) {
        return new GameStartContext(UUID.fromString("00000000-0000-0000-0000-000000005101"),
                ids.stream().map(id -> new GamePlayer(new ActorId(id), id)).toList(), new GameSettings(rounds, 20, 60, "all"),
                java.util.stream.IntStream.range(0, rounds).<GameContent>mapToObj(index -> new LiarWord(UUID.randomUUID(), "food", "제시어" + index, Set.of("word" + index))).toList(), NOW, new Random(11));
    }
}
