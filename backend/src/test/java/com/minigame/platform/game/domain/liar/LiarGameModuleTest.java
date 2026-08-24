package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameContent;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameSettings;
import com.minigame.platform.game.domain.GameStartContext;
import com.minigame.platform.room.domain.GameType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiarGameModuleTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000004001");
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final LiarGameModule module = new LiarGameModule();

    @Test
    void assigns_exactly_one_liar_at_every_supported_roster_boundary() {
        for (int playerCount = GameType.LIAR.minimumParticipants(); playerCount <= GameType.LIAR.maximumParticipants(); playerCount++) {
            var state = started(playerCount, 19L).state();

            assertThat(state.players()).hasSize(playerCount);
            assertThat(state.liarId()).isIn(state.players().stream().map(GamePlayer::actorId).toList());
            assertThat(state.liarBag()).doesNotContain(state.liarId()).hasSize(playerCount - 1);
        }
    }

    @Test
    void citizen_receives_word_while_liar_receives_only_category() {
        var state = started(4, 7L).state();
        var liarView = privateView(state, state.liarId());

        assertThat(liarView.role()).isEqualTo("LIAR");
        assertThat(liarView.category()).isEqualTo("food");
        assertThat(liarView.word()).isNull();
        assertThat(state.players()).filteredOn(player -> !player.actorId().equals(state.liarId())).allSatisfy(citizen -> {
            var citizenView = privateView(state, citizen.actorId());
            assertThat(citizenView.role()).isEqualTo("CITIZEN");
            assertThat(citizenView.word()).isEqualTo("붕어빵");
        });
    }

    @Test
    void public_projection_and_public_signals_never_contain_secret_word_aliases_or_liar_identity() {
        var transition = started(4, 11L);
        LiarGameState state = (LiarGameState) transition.state();
        var publicState = (LiarProjection.PublicState) module.project(state, state.players().getFirst().actorId()).publicState();

        assertThat(publicState.phase()).isEqualTo(LiarPhase.ROLE_REVEAL);
        assertThat(publicState.hints()).isEmpty();
        assertThat(publicState.submittedPlayerIds()).isEmpty();
        assertThat(publicState.toString()).doesNotContain("붕어빵", "fish-bread", state.liarId().value());
        assertThat(transition.transition().signals()).allSatisfy(signal -> {
            assertThat(signal.recipient()).isEmpty();
            assertThat(signal.payload().toString()).doesNotContain("붕어빵", "fish-bread", state.liarId().value());
        });
    }

    @Test
    void role_reveal_has_five_second_deadline_and_only_expiry_opens_the_first_hint_turn() {
        var started = started(4, 13L);
        var roleDeadline = started.deadline().orElseThrow();

        assertThat(started.state().phase()).isEqualTo(LiarPhase.ROLE_REVEAL);
        assertThat(roleDeadline).isEqualTo(new GameDeadline(SESSION_ID, 1, 1, NOW.plusSeconds(5)));
        assertViolation(() -> module.handle(started.state(), started.state().players().getFirst().actorId(), hint("겨울 음식"), NOW),
                "GAME_ACTION_NOT_ALLOWED");

        var hinting = module.expire(started.state(), roleDeadline, roleDeadline.at());
        var hintingState = (LiarGameState) hinting.state();

        assertThat(hintingState.phase()).isEqualTo(LiarPhase.HINTING);
        assertThat(hintingState.currentHinter()).isEqualTo(hintingState.hintOrder().getFirst());
        assertThat(hinting.deadline()).contains(new GameDeadline(SESSION_ID, 1, 2, NOW.plusSeconds(25)));
    }

    @Test
    void only_current_player_can_submit_one_sentence_without_the_word_or_an_alias() {
        var state = hinting(4, 17L).state();
        var otherPlayer = state.players().stream().map(GamePlayer::actorId)
                .filter(id -> !id.equals(state.currentHinter())).findFirst().orElseThrow();

        assertViolation(() -> module.handle(state, otherPlayer, hint("겨울 음식"), NOW), "GAME_NOT_YOUR_TURN");
        assertViolation(() -> module.handle(state, state.currentHinter(), hint("붕어빵 같아요"), NOW), "GAME_HINT_INVALID");
        assertViolation(() -> module.handle(state, state.currentHinter(), hint("fish bread"), NOW), "GAME_HINT_INVALID");
        assertViolation(() -> module.handle(state, state.currentHinter(), hint("첫 문장. 두 번째 문장!"), NOW), "GAME_HINT_INVALID");
        assertViolation(() -> module.handle(state, state.currentHinter(), hint("첫 문장\n두 번째"), NOW), "GAME_HINT_INVALID");
    }

    @Test
    void submitted_hint_is_public_then_actor_cannot_submit_it_again() {
        var state = hinting(4, 23L).state();
        var actor = state.currentHinter();

        var submitted = module.handle(state, actor, hint("겨울 간식"), NOW);
        var submittedState = (LiarGameState) submitted.state();

        assertThat(submittedState.hints()).containsEntry(actor, "겨울 간식");
        assertThat(((LiarProjection.PublicState) module.project(submittedState, actor).publicState()).submittedPlayerIds())
                .containsExactly(actor);
        assertViolation(() -> module.handle(submittedState, actor, hint("다른 힌트"), NOW), "GAME_ALREADY_SUBMITTED");
    }

    @Test
    void public_hint_snapshot_follows_authoritative_turn_order_after_submission_timeout_and_submission() {
        var firstTurn = hinting(4, 23L);
        var firstActor = firstTurn.state().currentHinter();
        var firstSubmitted = module.handle(firstTurn.state(), firstActor, hint("첫 번째 힌트"), firstTurn.deadline().orElseThrow().at().minusNanos(1));
        var afterFirst = (LiarGameState) firstSubmitted.state();
        var skippedActor = afterFirst.currentHinter();
        var afterTimeout = module.expire(afterFirst, firstSubmitted.deadline().orElseThrow(), firstSubmitted.deadline().orElseThrow().at());
        var afterSkip = (LiarGameState) afterTimeout.state();
        var thirdActor = afterSkip.currentHinter();
        var afterThird = module.handle(afterSkip, thirdActor, hint("세 번째 힌트"), afterTimeout.deadline().orElseThrow().at().minusNanos(1));
        var finalState = (LiarGameState) afterThird.state();

        var publicState = (LiarProjection.PublicState) module.project(finalState, firstActor).publicState();

        assertThat(skippedActor).isNotEqualTo(firstActor).isNotEqualTo(thirdActor);
        assertThat(publicState.hints()).containsExactly(
                new LiarProjection.PublicHint(firstActor, "첫 번째 힌트"),
                new LiarProjection.PublicHint(thirdActor, "세 번째 힌트")
        );
        assertThat(((LiarProjection.PublicState) module.project(finalState, thirdActor).publicState()).hints())
                .containsExactlyElementsOf(publicState.hints());
    }

    @Test
    void hint_submission_and_timeout_advance_in_order_then_last_hint_enters_discussing() {
        com.minigame.platform.game.domain.GameTransition transition = hinting(4, 29L).transition();
        LiarGameState state = (LiarGameState) transition.state();
        for (int index = 0; index < state.hintOrder().size(); index++) {
            var current = state.currentHinter();
            transition = index == 1
                    ? module.expire(state, transition.deadline().orElseThrow(), transition.deadline().orElseThrow().at())
                    : module.handle(state, current, hint("힌트" + index), transition.deadline().orElseThrow().at().minusNanos(1));
            state = (LiarGameState) transition.state();
            if (index < 3) {
                assertThat(state.phase()).isEqualTo(LiarPhase.HINTING);
                assertThat(state.currentHinter()).isEqualTo(state.hintOrder().get(index + 1));
                assertThat(transition.deadline().orElseThrow().at()).isAfter(NOW);
            }
        }

        assertThat(state.phase()).isEqualTo(LiarPhase.DISCUSSING);
        assertThat(state.hints()).hasSize(3);
        assertThat(transition.deadline().orElseThrow().at()).isAfter(NOW);
        assertThat(transition.completed()).isFalse();
    }

    @Test
    void stale_or_early_deadlines_do_not_advance_the_state() {
        var transition = started(4, 31L);
        var state = transition.state();
        var expected = transition.deadline().orElseThrow();

        var early = module.expire(state, expected, NOW.plusSeconds(4));
        var stale = module.expire(state, new GameDeadline(SESSION_ID, 1, 99, expected.at()), expected.at());

        assertThat(early.state()).isSameAs(state);
        assertThat(early.deadline()).contains(expected);
        assertThat(stale.state()).isSameAs(state);
        assertThat(stale.deadline()).contains(expected);
    }

    @Test
    void rejects_invalid_rosters_and_non_liar_content() {
        assertViolation(() -> module.start(context(3, List.of(word()), 37L)), "GAME_START_CONDITION_NOT_MET");
        assertViolation(() -> module.start(context(11, List.of(word()), 37L)), "GAME_START_CONDITION_NOT_MET");
        assertViolation(() -> module.start(context(4, List.of(new OtherContent(UUID.randomUUID())), 37L)), "GAME_CONTENT_INVALID");
    }

    private Started started(int players, long seed) {
        var transition = module.start(context(players, List.of(word()), seed));
        return new Started((LiarGameState) transition.state(), transition);
    }

    private Started hinting(int players, long seed) {
        var started = started(players, seed);
        var transition = module.expire(started.state(), started.transition().deadline().orElseThrow(), NOW.plusSeconds(5));
        return new Started((LiarGameState) transition.state(), transition);
    }

    private static GameStartContext context(int count, List<? extends GameContent> contents, long seed) {
        var players = new ArrayList<GamePlayer>();
        for (int index = 1; index <= count; index++) {
            players.add(new GamePlayer(new ActorId("player-" + index), "player " + index));
        }
        return new GameStartContext(SESSION_ID, players, new GameSettings(1, 20, 60, "all"), List.copyOf(contents), NOW, new Random(seed));
    }

    private static LiarWord word() {
        return new LiarWord(UUID.fromString("00000000-0000-0000-0000-000000004002"), "food", "붕어빵", Set.of("fish-bread", "fish bread"));
    }

    private static GameAction hint(String text) {
        return new GameAction("HINT_SUBMIT", Map.of("hint", text));
    }

    private static LiarProjection.PrivateState privateView(LiarGameState state, ActorId actorId) {
        return (LiarProjection.PrivateState) new LiarGameModule().project(state, actorId).privateState().orElseThrow();
    }

    private static void assertViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).hasMessage(code);
    }

    private record Started(LiarGameState state, com.minigame.platform.game.domain.GameTransition transition) {
        private java.util.Optional<GameDeadline> deadline() {
            return transition.deadline();
        }
    }

    private record OtherContent(UUID id) implements GameContent {
    }
}
