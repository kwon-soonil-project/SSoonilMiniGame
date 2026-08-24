package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameModule;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameProjection;
import com.minigame.platform.game.domain.GameRuleViolation;
import com.minigame.platform.game.domain.GameStartContext;
import com.minigame.platform.game.domain.GameState;
import com.minigame.platform.game.domain.GameTransition;
import com.minigame.platform.game.domain.GameSignal;
import com.minigame.platform.room.domain.GameType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

public final class LiarGameModule implements GameModule {
    private static final String HINT_SUBMIT = "HINT_SUBMIT";
    private static final int ROLE_REVEAL_SECONDS = 5;

    @Override
    public GameType type() {
        return GameType.LIAR;
    }

    @Override
    public GameTransition start(GameStartContext context) {
        validateRoster(context.players());
        var words = context.contents().stream().map(content -> {
            if (!(content instanceof LiarWord word)) {
                throw violation("GAME_CONTENT_INVALID");
            }
            return word;
        }).toList();
        var liarBag = shuffledActorIds(context.players(), context.random());
        var liar = liarBag.removeFirst();
        var hintOrder = shuffledActorIds(context.players(), context.random());
        var state = new LiarGameState(
                context.sessionId(), 1, 1, context.settings().actionSeconds(), context.settings().discussionSeconds(),
                LiarPhase.ROLE_REVEAL, context.now().plusSeconds(ROLE_REVEAL_SECONDS), context.players(), words, 0,
                liarBag, liar, hintOrder, 0, Map.of()
        );
        return transition(state, List.of(phaseSignal(state)));
    }

    @Override
    public GameTransition handle(GameState gameState, ActorId actorId, GameAction action, Instant now) {
        var state = liarState(gameState);
        if (state.phase() != LiarPhase.HINTING || !HINT_SUBMIT.equals(action.type())) {
            throw violation("GAME_ACTION_NOT_ALLOWED");
        }
        if (state.hints().containsKey(actorId)) {
            throw violation("GAME_ALREADY_SUBMITTED");
        }
        if (!actorId.equals(state.currentHinter())) {
            throw violation("GAME_NOT_YOUR_TURN");
        }
        var hint = hintText(action);
        if (!isValidHint(hint, state.word())) {
            throw violation("GAME_HINT_INVALID");
        }
        var hints = new HashMap<>(state.hints());
        hints.put(actorId, hint.strip());
        return advanceHint(state, hints, now, GameSignal.publicSignal("HINT_SUBMITTED", Map.of("playerId", actorId, "hint", hint.strip())));
    }

    @Override
    public GameTransition expire(GameState gameState, GameDeadline expected, Instant now) {
        var state = liarState(gameState);
        if (!matches(state, expected) || now.isBefore(expected.at())) {
            return unchanged(state);
        }
        if (state.phase() == LiarPhase.ROLE_REVEAL) {
            var next = withPhase(state, LiarPhase.HINTING, state.hintIndex(), state.hints(), now.plusSeconds(state.actionSeconds()));
            return transition(next, List.of(phaseSignal(next)));
        }
        if (state.phase() == LiarPhase.HINTING) {
            return advanceHint(state, state.hints(), now,
                    GameSignal.publicSignal("HINT_SKIPPED", Map.of("playerId", state.currentHinter())));
        }
        return unchanged(state);
    }

    @Override
    public GameTransition removePlayer(GameState state, ActorId actorId, Instant now) {
        throw violation("GAME_ACTION_NOT_ALLOWED");
    }

    @Override
    public GameTransition synchronizePlayers(GameState state, List<GamePlayer> players, Instant now) {
        throw violation("GAME_ACTION_NOT_ALLOWED");
    }

    @Override
    public GameProjection project(GameState gameState, ActorId viewer) {
        var state = liarState(gameState);
        var hints = state.hints().entrySet().stream()
                .map(entry -> new LiarProjection.PublicHint(entry.getKey(), entry.getValue()))
                .toList();
        var publicState = new LiarProjection.PublicState(
                state.round(), state.phase(), state.deadlineAt(), state.currentHinter(), hints, state.hints().keySet()
        );
        if (state.players().stream().noneMatch(player -> player.actorId().equals(viewer))) {
            return new GameProjection(publicState, Optional.empty());
        }
        var liar = viewer.equals(state.liarId());
        var privateState = new LiarProjection.PrivateState(
                liar ? "LIAR" : "CITIZEN", state.word().categoryCode(), liar ? null : state.word().answer(),
                state.hints().containsKey(viewer), false
        );
        return new GameProjection(publicState, Optional.of(privateState));
    }

    private GameTransition advanceHint(LiarGameState state, Map<ActorId, String> hints, Instant now, GameSignal signal) {
        var nextIndex = state.hintIndex() + 1;
        if (nextIndex == state.hintOrder().size()) {
            var next = withPhase(state, LiarPhase.DISCUSSING, nextIndex, hints, now.plusSeconds(state.discussionSeconds()));
            return transition(next, List.of(signal, phaseSignal(next)));
        }
        var next = withPhase(state, LiarPhase.HINTING, nextIndex, hints, now.plusSeconds(state.actionSeconds()));
        return transition(next, List.of(signal, phaseSignal(next)));
    }

    private static LiarGameState withPhase(LiarGameState state, LiarPhase phase, int hintIndex, Map<ActorId, String> hints, Instant deadlineAt) {
        return new LiarGameState(
                state.sessionId(), state.round(), state.phaseVersion() + 1, state.actionSeconds(), state.discussionSeconds(),
                phase, deadlineAt, state.players(), state.words(), state.wordIndex(), state.liarBag(), state.liarId(),
                state.hintOrder(), hintIndex, hints
        );
    }

    private static GameTransition transition(LiarGameState state, List<GameSignal> signals) {
        return new GameTransition(state, signals, Map.of(), Optional.of(deadline(state)), false);
    }

    private static GameTransition unchanged(LiarGameState state) {
        return new GameTransition(state, List.of(), Map.of(), Optional.of(deadline(state)), false);
    }

    private static GameDeadline deadline(LiarGameState state) {
        return new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());
    }

    private static GameSignal phaseSignal(LiarGameState state) {
        return GameSignal.publicSignal("GAME_PHASE_CHANGED", Map.of("round", state.round(), "phase", state.phase(), "deadlineAt", state.deadlineAt()));
    }

    private static boolean matches(LiarGameState state, GameDeadline expected) {
        return expected.matches(state.sessionId(), state.round(), state.phaseVersion()) && expected.at().equals(state.deadlineAt());
    }

    private static String hintText(GameAction action) {
        var value = action.data().get("hint");
        if (!(value instanceof String hint) || hint.isBlank()) {
            throw violation("GAME_HINT_INVALID");
        }
        return hint;
    }

    private static boolean isValidHint(String hint, LiarWord word) {
        if (hint.matches(".*[\\r\\n\\u2028\\u2029].*") || sentenceEndingCount(hint) >= 2) {
            return false;
        }
        var normalizedHint = TextNormalizer.normalize(hint);
        if (normalizedHint.isEmpty() || normalizedHint.contains(TextNormalizer.normalize(word.answer()))) {
            return false;
        }
        return word.aliases().stream().map(TextNormalizer::normalize)
                .filter(alias -> !alias.isEmpty()).noneMatch(normalizedHint::contains);
    }

    private static long sentenceEndingCount(String hint) {
        return hint.codePoints().filter(point -> point == '.' || point == '!' || point == '?' || point == '。' || point == '！' || point == '？').count();
    }

    private static ArrayList<ActorId> shuffledActorIds(List<GamePlayer> players, RandomGenerator random) {
        var result = new ArrayList<>(players.stream().map(GamePlayer::actorId).toList());
        for (var index = result.size() - 1; index > 0; index--) {
            var swapIndex = random.nextInt(index + 1);
            var current = result.get(index);
            result.set(index, result.get(swapIndex));
            result.set(swapIndex, current);
        }
        return result;
    }

    private static void validateRoster(List<GamePlayer> players) {
        if (players.size() < GameType.LIAR.minimumParticipants() || players.size() > GameType.LIAR.maximumParticipants()) {
            throw violation("GAME_START_CONDITION_NOT_MET");
        }
        var ids = new HashSet<ActorId>();
        if (!players.stream().map(GamePlayer::actorId).allMatch(ids::add)) {
            throw violation("GAME_START_CONDITION_NOT_MET");
        }
    }

    private static LiarGameState liarState(GameState state) {
        if (!(state instanceof LiarGameState liarState)) {
            throw violation("GAME_STATE_INVALID");
        }
        return liarState;
    }

    private static GameRuleViolation violation(String code) {
        return new GameRuleViolation(code);
    }
}
