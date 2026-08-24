package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameSettings;
import com.minigame.platform.game.domain.GameStartContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiarVotingTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final LiarGameModule module = new LiarGameModule();

    @Test
    void host_proposal_counts_as_yes_and_majority_ends_discussion() {
        var state = discussing();
        var host = state.players().getFirst().actorId();
        var player2 = state.players().get(1).actorId();
        var player3 = state.players().get(2).actorId();

        var proposed = module.handle(state, host, action("DISCUSSION_END_PROPOSE", Map.of()), NOW);
        var secondVote = module.handle(proposed.state(), player2, action("DISCUSSION_END_VOTE", Map.of("agree", true)), NOW);
        var voted = module.handle(secondVote.state(), player3, action("DISCUSSION_END_VOTE", Map.of("agree", true)), NOW);

        assertThat(((LiarGameState) voted.state()).phase()).isEqualTo(LiarPhase.VOTING);
    }

    @Test
    void vote_rejects_self_target_and_duplicate_submission() {
        var state = voting();
        var voter = state.players().getFirst().actorId();
        var target = state.players().get(1).actorId();

        assertThatThrownBy(() -> module.handle(state, voter, action("VOTE_SUBMIT", Map.of("targetActorId", voter.value())), NOW))
                .hasMessage("GAME_TARGET_INVALID");
        var submitted = module.handle(state, voter, action("VOTE_SUBMIT", Map.of("targetActorId", target.value())), NOW);
        assertThatThrownBy(() -> module.handle(submitted.state(), voter, action("VOTE_SUBMIT", Map.of("targetActorId", target.value())), NOW))
                .hasMessage("GAME_ALREADY_SUBMITTED");
    }

    @Test
    void first_tie_enters_revote_and_second_tie_awards_liar_three_points() {
        var transition = voting();
        var state = transition;
        var players = state.players().stream().map(GamePlayer::actorId).toList();
        var firstTargets = List.of(players.get(1), players.get(0), players.get(1), players.get(0));
        for (int i = 0; i < players.size(); i++) {
            transition = (LiarGameState) module.handle(transition, players.get(i), action("VOTE_SUBMIT", Map.of("targetActorId", firstTargets.get(i).value())), NOW).state();
        }
        assertThat(transition.phase()).isEqualTo(LiarPhase.REVOTING);
        for (int i = 0; i < players.size(); i++) {
            var target = i % 2 == 0 ? players.get(1) : players.get(0);
            var result = module.handle(transition, players.get(i), action("REVOTE_SUBMIT", Map.of("targetActorId", target.value())), NOW);
            transition = (LiarGameState) result.state();
            if (i == players.size() - 1) {
                assertThat(transition.phase()).isEqualTo(LiarPhase.ROUND_RESULT);
                assertThat(result.scoreDeltas()).containsEntry(transition.liarId(), 3);
            }
        }
    }

    @Test
    void full_abstention_on_voting_timeout_awards_liar_three_points() {
        var state = voting();
        var deadline = new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());

        var result = module.expire(state, deadline, deadline.at());

        assertThat(((LiarGameState) result.state()).phase()).isEqualTo(LiarPhase.ROUND_RESULT);
        assertThat(result.scoreDeltas()).containsEntry(state.liarId(), 3);
    }

    @Test
    void accused_liar_can_use_a_normalized_alias_for_the_two_point_comeback() {
        var state = accusedLiar();

        var result = module.handle(state, state.liarId(), action("LIAR_GUESS_SUBMIT", Map.of("answer", " Fish-bread! ")), NOW);

        assertThat(((LiarGameState) result.state()).roundResult().liarGuessedCorrectly()).isTrue();
        assertThat(result.scoreDeltas()).containsOnly(Map.entry(state.liarId(), 2));
    }

    @Test
    void accused_liar_guess_timeout_awards_each_active_citizen_one_point() {
        var state = accusedLiar();
        var deadline = new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());

        var result = module.expire(state, deadline, deadline.at());

        assertThat(((LiarGameState) result.state()).roundResult().liarGuessedCorrectly()).isFalse();
        assertThat(result.scoreDeltas()).hasSize(3).doesNotContainKey(state.liarId());
        assertThat(result.scoreDeltas().values()).allMatch(score -> score == 1);
    }

    private LiarGameState discussing() {
        var transition = module.start(context());
        var state = (LiarGameState) transition.state();
        transition = module.expire(state, transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at());
        state = (LiarGameState) transition.state();
        while (state.phase() == LiarPhase.HINTING) {
            transition = module.expire(state, transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at());
            state = (LiarGameState) transition.state();
        }
        return state;
    }

    private LiarGameState voting() {
        var state = discussing();
        var deadline = new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());
        return (LiarGameState) module.expire(state, deadline, deadline.at()).state();
    }

    private LiarGameState accusedLiar() {
        LiarGameState state = voting();
        var liarId = state.liarId();
        var alternate = state.players().stream().map(GamePlayer::actorId).filter(id -> !id.equals(liarId)).findFirst().orElseThrow();
        for (var player : state.players()) {
            var target = player.actorId().equals(liarId) ? alternate : liarId;
            state = (LiarGameState) module.handle(state, player.actorId(), action("VOTE_SUBMIT", Map.of("targetActorId", target.value())), NOW).state();
        }
        assertThat(state.phase()).isEqualTo(LiarPhase.LIAR_GUESSING);
        return state;
    }

    private static GameStartContext context() {
        var players = List.of("a", "b", "c", "d").stream().map(id -> new GamePlayer(new ActorId(id), id)).toList();
        return new GameStartContext(UUID.fromString("00000000-0000-0000-0000-000000005001"), players,
                new GameSettings(2, 20, 60, "all"), List.of(
                new LiarWord(UUID.randomUUID(), "food", "붕어빵", Set.of("fish bread")),
                new LiarWord(UUID.randomUUID(), "animal", "호랑이", Set.of("tiger"))), NOW, new Random(7));
    }

    private static GameAction action(String type, Map<String, Object> data) {
        return new GameAction(type, data);
    }
}
