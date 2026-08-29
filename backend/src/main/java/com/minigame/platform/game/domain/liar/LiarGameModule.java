package com.minigame.platform.game.domain.liar;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameModule;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameProjection;
import com.minigame.platform.game.domain.GameRuleViolation;
import com.minigame.platform.game.domain.GameSignal;
import com.minigame.platform.game.domain.GameStartContext;
import com.minigame.platform.game.domain.GameState;
import com.minigame.platform.game.domain.GameTransition;
import com.minigame.platform.room.domain.GameType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class LiarGameModule implements GameModule {
    private static final String HINT_SUBMIT = "HINT_SUBMIT";
    private static final String DISCUSSION_END_PROPOSE = "DISCUSSION_END_PROPOSE";
    private static final String DISCUSSION_END_VOTE = "DISCUSSION_END_VOTE";
    private static final String VOTE_SUBMIT = "VOTE_SUBMIT";
    private static final String REVOTE_SUBMIT = "REVOTE_SUBMIT";
    private static final String LIAR_GUESS_SUBMIT = "LIAR_GUESS_SUBMIT";
    private static final int ROLE_REVEAL_SECONDS = 5;
    private static final int ROUND_RESULT_SECONDS = 8;
    private static final int GAME_RESULT_SECONDS = 60;

    @Override public GameType type() { return GameType.LIAR; }

    @Override
    public GameTransition start(GameStartContext context) {
        validateRoster(context.players());
        var words = context.contents().stream().map(content -> {
            if (!(content instanceof LiarWord word)) throw violation("GAME_CONTENT_INVALID");
            return word;
        }).toList();
        if (words.size() < context.settings().rounds()) throw violation("GAME_CONTENT_INVALID");
        var bag = shuffledActorIds(context.players(), context.random());
        var liar = bag.removeFirst();
        var hints = shuffledActorIds(context.players(), context.random());
        var state = new LiarGameState(context.sessionId(), 1, 1, context.settings().rounds(), context.settings().actionSeconds(), context.settings().discussionSeconds(),
                LiarPhase.ROLE_REVEAL, context.now().plusSeconds(ROLE_REVEAL_SECONDS), context.players(), words, 0, bag, context.random().nextLong(), liar, hints, 0,
                Map.of(), Set.of(), Set.of(), Map.of(), Set.of(), false, null);
        return transition(state, List.of(phaseSignal(state)));
    }

    @Override
    public GameTransition handle(GameState gameState, ActorId actorId, GameAction action, Instant now) {
        var state = liarState(gameState);
        if (!now.isBefore(state.deadlineAt())) throw violation("GAME_ACTION_NOT_ALLOWED");
        return switch (state.phase()) {
            case HINTING -> handleHint(state, actorId, action, now);
            case DISCUSSING -> handleDiscussion(state, actorId, action, now);
            case VOTING -> handleVote(state, actorId, action, now, false);
            case REVOTING -> handleVote(state, actorId, action, now, true);
            case LIAR_GUESSING -> handleGuess(state, actorId, action, now);
            default -> throw violation("GAME_ACTION_NOT_ALLOWED");
        };
    }

    @Override
    public GameTransition expire(GameState gameState, GameDeadline expected, Instant now) {
        var state = liarState(gameState);
        if (!matches(state, expected) || now.isBefore(expected.at())) return unchanged(state);
        return switch (state.phase()) {
            case ROLE_REVEAL -> enterHinting(state, now);
            case HINTING -> advanceHint(state, state.hints(), now, GameSignal.publicSignal("HINT_SKIPPED", Map.of("playerId", state.currentHinter())));
            case DISCUSSING -> enterVoting(state, now);
            case VOTING, REVOTING -> resolveVote(state, now);
            case LIAR_GUESSING -> finishRound(state, LiarGameState.RoundResult.citizensWon(state.liarId()), now);
            case ROUND_RESULT -> unchanged(state);
            case GAME_RESULT -> new GameTransition(state, List.of(), Map.of(), Optional.empty(), true);
        };
    }

    @Override
    public GameTransition removePlayer(GameState gameState, ActorId actorId, Instant now) {
        var state = liarState(gameState);
        if (state.phase() == LiarPhase.ROUND_RESULT || state.phase() == LiarPhase.GAME_RESULT) return unchanged(state);
        if (!activeIds(state).contains(actorId)) return unchanged(state);
        var remaining = state.players().stream().filter(player -> !player.actorId().equals(actorId)).toList();
        var departedHintIndex = state.hintOrder().indexOf(actorId);
        var currentHinterDeparted = state.phase() == LiarPhase.HINTING && actorId.equals(state.currentHinter());
        var removeFutureHintTurn = state.phase() == LiarPhase.ROLE_REVEAL
                || (state.phase() == LiarPhase.HINTING
                && departedHintIndex > state.hintIndex()
                && !state.hints().containsKey(actorId));
        var retainedHintOrder = removeFutureHintTurn
                ? state.hintOrder().stream().filter(id -> !id.equals(actorId)).toList()
                : state.hintOrder();
        var reduced = copy(state, remaining, state.liarBag().stream().filter(id -> !id.equals(actorId)).toList(), state.randomState(),
                retainedHintOrder, state.hintIndex(), state.hints(),
                withoutActor(state.discussionEndVotes(), actorId), withoutActor(state.discussionEndRespondents(), actorId), withoutVote(state.votes(), actorId),
                withoutActor(state.revoteCandidates(), actorId), state.liarGuessSubmitted(), state.roundResult());
        if (actorId.equals(state.liarId()) || remaining.size() < GameType.LIAR.minimumParticipants()) return finishRound(reduced, LiarGameState.RoundResult.invalidatedRound(), now);
        if (currentHinterDeparted) {
            return advanceHint(reduced, reduced.hints(), now,
                    GameSignal.publicSignal("HINT_SKIPPED", Map.of("playerId", actorId)));
        }
        if ((state.phase() == LiarPhase.VOTING || state.phase() == LiarPhase.REVOTING) && allEligibleVoted(reduced)) return resolveVote(reduced, now);
        return transition(reduced, List.of(GameSignal.publicSignal("PLAYER_LEFT_GAME", Map.of("playerId", actorId))));
    }

    @Override
    public GameTransition synchronizePlayers(GameState gameState, List<GamePlayer> players, Instant now) {
        var state = liarState(gameState);
        validateUniqueRoster(players);
        if (state.phase() == LiarPhase.ROUND_RESULT) {
            if (state.round() == state.totalRounds() || players.size() < GameType.LIAR.minimumParticipants()) {
                return enterGameResult(state, players, now);
            }
            return nextRound(state, players, now);
        }
        var departing = state.players().stream().map(GamePlayer::actorId).filter(id -> players.stream().noneMatch(player -> player.actorId().equals(id))).toList();
        GameTransition transition = unchanged(state);
        for (var departed : departing) transition = removePlayer(transition.state(), departed, now);
        return transition;
    }

    @Override
    public GameProjection project(GameState gameState, ActorId viewer) {
        var state = liarState(gameState);
        var hints = state.hintOrder().stream().filter(state.hints()::containsKey).map(id -> new LiarProjection.PublicHint(id, state.hints().get(id))).toList();
        var processedHintCount = Math.min(state.hintIndex(), state.hintOrder().size());
        var hintStatuses = state.hintOrder().subList(0, processedHintCount).stream()
                .map(id -> new LiarProjection.PublicHintStatus(
                        id, state.hints().containsKey(id) ? "SUBMITTED" : "SKIPPED"
                )).toList();
        var submitted = switch (state.phase()) {
            case HINTING -> state.hints().keySet();
            case DISCUSSING -> state.discussionEndRespondents();
            case VOTING, REVOTING -> state.votes().keySet();
            case LIAR_GUESSING -> state.liarGuessSubmitted() ? Set.of(state.liarId()) : Set.<ActorId>of();
            default -> Set.<ActorId>of();
        };
        var resultPhase = state.phase() == LiarPhase.ROUND_RESULT || state.phase() == LiarPhase.GAME_RESULT;
        var publicState = new LiarProjection.PublicState(
                state.round(), state.phase(), state.deadlineAt(), state.currentHinter(), hints, hintStatuses, submitted,
                state.phase() == LiarPhase.REVOTING ? state.revoteCandidates() : Set.of(),
                resultPhase ? state.liarId() : null,
                resultPhase ? state.word().answer() : null,
                resultPhase ? state.roundResult() : null
        );
        if (!activeIds(state).contains(viewer)) return new GameProjection(publicState, Optional.empty());
        var liar = viewer.equals(state.liarId());
        return new GameProjection(publicState, Optional.of(new LiarProjection.PrivateState(liar ? "LIAR" : "CITIZEN", state.word().categoryCode(), liar ? null : state.word().answer(), state.hints().containsKey(viewer), state.votes().containsKey(viewer))));
    }

    private GameTransition enterHinting(LiarGameState state, Instant now) {
        var next = withPhase(state, LiarPhase.HINTING, now.plusSeconds(state.actionSeconds()));
        return transition(next, List.of(phaseSignal(next)));
    }

    private GameTransition handleHint(LiarGameState state, ActorId actorId, GameAction action, Instant now) {
        if (!HINT_SUBMIT.equals(action.type())) throw violation("GAME_ACTION_NOT_ALLOWED");
        if (state.hints().containsKey(actorId)) throw violation("GAME_ALREADY_SUBMITTED");
        if (!actorId.equals(state.currentHinter())) throw violation("GAME_NOT_YOUR_TURN");
        var hint = hintText(action);
        if (!isValidHint(hint, state.word())) throw violation("GAME_HINT_INVALID");
        var hints = new HashMap<>(state.hints()); hints.put(actorId, hint.strip());
        return advanceHint(state, hints, now, GameSignal.publicSignal("HINT_SUBMITTED", Map.of("playerId", actorId, "hint", hint.strip())));
    }

    private GameTransition handleDiscussion(LiarGameState state, ActorId actorId, GameAction action, Instant now) {
        requireActive(state, actorId);
        if (DISCUSSION_END_PROPOSE.equals(action.type())) {
            if (!state.discussionEndRespondents().isEmpty()) throw violation("GAME_ALREADY_SUBMITTED");
            var proposed = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), Set.of(actorId), Set.of(actorId), state.votes(), state.revoteCandidates(), false, null);
            return majority(proposed) ? enterVoting(proposed, now) : transition(proposed, List.of(GameSignal.publicSignal("DISCUSSION_END_PROPOSED", Map.of("playerId", actorId))));
        }
        if (!DISCUSSION_END_VOTE.equals(action.type()) || state.discussionEndRespondents().isEmpty()) throw violation("GAME_ACTION_NOT_ALLOWED");
        if (state.discussionEndRespondents().contains(actorId)) throw violation("GAME_ALREADY_SUBMITTED");
        if (!(action.data().get("agree") instanceof Boolean agree)) throw violation("GAME_ACTION_NOT_ALLOWED");
        var respondents = new LinkedHashSet<>(state.discussionEndRespondents()); respondents.add(actorId);
        var yes = new LinkedHashSet<>(state.discussionEndVotes()); if (agree) yes.add(actorId);
        var voted = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), yes, respondents, state.votes(), state.revoteCandidates(), false, null);
        return majority(voted) ? enterVoting(voted, now) : transition(voted, List.of(GameSignal.publicSignal("DISCUSSION_END_VOTED", Map.of("playerId", actorId))));
    }

    private GameTransition enterVoting(LiarGameState state, Instant now) {
        var voting = phaseCopy(state, LiarPhase.VOTING, now.plusSeconds(state.actionSeconds()), Map.of(), Set.of(), false, null);
        return transition(voting, List.of(phaseSignal(voting)));
    }

    private GameTransition handleVote(LiarGameState state, ActorId actorId, GameAction action, Instant now, boolean revote) {
        if (!(revote ? REVOTE_SUBMIT : VOTE_SUBMIT).equals(action.type())) throw violation("GAME_ACTION_NOT_ALLOWED");
        requireActive(state, actorId);
        if (state.votes().containsKey(actorId)) throw violation("GAME_ALREADY_SUBMITTED");
        var target = target(action);
        var validTargets = revote ? state.revoteCandidates() : activeIds(state);
        if (target.equals(actorId) || !validTargets.contains(target)) throw violation("GAME_TARGET_INVALID");
        var votes = new HashMap<>(state.votes()); votes.put(actorId, target);
        var voted = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), state.discussionEndVotes(), state.discussionEndRespondents(), votes, state.revoteCandidates(), false, null);
        return allEligibleVoted(voted) ? resolveVote(voted, now) : transition(voted, List.of(GameSignal.publicSignal("VOTE_SUBMITTED", Map.of("playerId", actorId))));
    }

    private GameTransition resolveVote(LiarGameState state, Instant now) {
        var active = activeIds(state); var counts = new HashMap<ActorId, Integer>();
        state.votes().forEach((voter, target) -> { if (active.contains(voter) && active.contains(target)) counts.merge(target, 1, Integer::sum); });
        if (counts.isEmpty()) return finishRound(state, LiarGameState.RoundResult.liarSurvived(), now);
        var highest = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        Set<ActorId> leaders = counts.entrySet().stream().filter(entry -> entry.getValue() == highest).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (leaders.size() > 1) {
            if (state.phase() == LiarPhase.REVOTING) return finishRound(state, LiarGameState.RoundResult.liarSurvived(), now);
            var revoting = phaseCopy(state, LiarPhase.REVOTING, now.plusSeconds(state.actionSeconds()), Map.of(), leaders, false, null);
            return transition(revoting, List.of(phaseSignal(revoting)));
        }
        var accused = leaders.iterator().next();
        if (!accused.equals(state.liarId())) return finishRound(state, LiarGameState.RoundResult.liarSurvived(), now);
        var guessing = phaseCopy(state, LiarPhase.LIAR_GUESSING, now.plusSeconds(state.actionSeconds()), Map.of(), Set.of(), false, null);
        return transition(guessing, List.of(phaseSignal(guessing)));
    }

    private GameTransition handleGuess(LiarGameState state, ActorId actorId, GameAction action, Instant now) {
        if (!LIAR_GUESS_SUBMIT.equals(action.type()) || !actorId.equals(state.liarId())) throw violation("GAME_ACTION_NOT_ALLOWED");
        if (state.liarGuessSubmitted()) throw violation("GAME_ALREADY_SUBMITTED");
        if (!(action.data().get("answer") instanceof String answer) || TextNormalizer.normalize(answer).isEmpty()) throw violation("GAME_ACTION_NOT_ALLOWED");
        var normalized = TextNormalizer.normalize(answer);
        var correct = normalized.equals(TextNormalizer.normalize(state.word().answer())) || state.word().aliases().stream().map(TextNormalizer::normalize).anyMatch(normalized::equals);
        var guessed = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), state.discussionEndVotes(), state.discussionEndRespondents(), state.votes(), state.revoteCandidates(), true, null);
        return finishRound(guessed, correct
                ? LiarGameState.RoundResult.liarComeback(state.liarId())
                : LiarGameState.RoundResult.citizensWon(state.liarId()), now);
    }

    private GameTransition advanceHint(LiarGameState state, Map<ActorId, String> hints, Instant now, GameSignal signal) {
        var nextIndex = state.hintIndex() + 1;
        if (nextIndex == state.hintOrder().size()) {
            var next = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), nextIndex, hints, Set.of(), Set.of(), Map.of(), Set.of(), false, null, LiarPhase.DISCUSSING, now.plusSeconds(state.discussionSeconds()), state.phaseVersion() + 1);
            return transition(next, List.of(signal, phaseSignal(next)));
        }
        var next = copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), nextIndex, hints, state.discussionEndVotes(), state.discussionEndRespondents(), state.votes(), state.revoteCandidates(), false, null, LiarPhase.HINTING, now.plusSeconds(state.actionSeconds()), state.phaseVersion() + 1);
        return transition(next, List.of(signal, phaseSignal(next)));
    }

    private GameTransition finishRound(LiarGameState state, LiarGameState.RoundResult result, Instant now) {
        var finished = phaseCopy(state, LiarPhase.ROUND_RESULT, now.plusSeconds(ROUND_RESULT_SECONDS), Map.of(), Set.of(), false, result);
        return transition(finished, List.of(GameSignal.publicSignal("ROUND_RESULT", resultPayload(finished)), phaseSignal(finished)), LiarScoring.score(finished, result));
    }

    private GameTransition enterGameResult(LiarGameState state, List<GamePlayer> players, Instant now) {
        var roster = copy(
                state, players, state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(),
                state.discussionEndVotes(), state.discussionEndRespondents(), state.votes(), state.revoteCandidates(),
                state.liarGuessSubmitted(), state.roundResult()
        );
        var result = phaseCopy(roster, LiarPhase.GAME_RESULT, now.plusSeconds(GAME_RESULT_SECONDS), Map.of(), Set.of(), false, state.roundResult());
        return transition(result, List.of(phaseSignal(result)));
    }

    private GameTransition nextRound(LiarGameState state, List<GamePlayer> players, Instant now) {
        validateRoster(players);
        Set<ActorId> ids = players.stream().map(GamePlayer::actorId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var bag = new ArrayList<>(state.liarBag().stream().filter(ids::contains).toList());
        var randomState = state.randomState();
        if (bag.isEmpty()) { var shuffled = shuffle(ids, randomState); bag.addAll(shuffled.ids()); randomState = shuffled.randomState(); }
        var liar = bag.removeFirst(); var hints = shuffle(ids, randomState);
        var next = new LiarGameState(state.sessionId(), state.round() + 1, state.phaseVersion() + 1, state.totalRounds(), state.actionSeconds(), state.discussionSeconds(), LiarPhase.ROLE_REVEAL, now.plusSeconds(ROLE_REVEAL_SECONDS), players, state.words(), state.wordIndex() + 1, bag, hints.randomState(), liar, hints.ids(), 0, Map.of(), Set.of(), Set.of(), Map.of(), Set.of(), false, null);
        return transition(next, List.of(phaseSignal(next)));
    }

    private static LiarGameState withPhase(LiarGameState state, LiarPhase phase, Instant deadline) { return copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), state.discussionEndVotes(), state.discussionEndRespondents(), state.votes(), state.revoteCandidates(), state.liarGuessSubmitted(), state.roundResult(), phase, deadline, state.phaseVersion() + 1); }
    private static LiarGameState phaseCopy(LiarGameState state, LiarPhase phase, Instant deadline, Map<ActorId, ActorId> votes, Set<ActorId> candidates, boolean guessed, LiarGameState.RoundResult result) { return copy(state, state.players(), state.liarBag(), state.randomState(), state.hintOrder(), state.hintIndex(), state.hints(), Set.of(), Set.of(), votes, candidates, guessed, result, phase, deadline, state.phaseVersion() + 1); }
    private static LiarGameState copy(LiarGameState state, List<GamePlayer> players, List<ActorId> bag, long randomState, List<ActorId> hints, int hintIndex, Map<ActorId, String> hintValues, Set<ActorId> endVotes, Set<ActorId> endRespondents, Map<ActorId, ActorId> votes, Set<ActorId> candidates, boolean guessed, LiarGameState.RoundResult result) { return copy(state, players, bag, randomState, hints, hintIndex, hintValues, endVotes, endRespondents, votes, candidates, guessed, result, state.phase(), state.deadlineAt(), state.phaseVersion()); }
    private static LiarGameState copy(LiarGameState state, List<GamePlayer> players, List<ActorId> bag, long randomState, List<ActorId> hints, int hintIndex, Map<ActorId, String> hintValues, Set<ActorId> endVotes, Set<ActorId> endRespondents, Map<ActorId, ActorId> votes, Set<ActorId> candidates, boolean guessed, LiarGameState.RoundResult result, LiarPhase phase, Instant deadline, int version) { return new LiarGameState(state.sessionId(), state.round(), version, state.totalRounds(), state.actionSeconds(), state.discussionSeconds(), phase, deadline, players, state.words(), state.wordIndex(), bag, randomState, state.liarId(), hints, hintIndex, hintValues, endVotes, endRespondents, votes, candidates, guessed, result); }
    private static GameTransition transition(LiarGameState state, List<GameSignal> signals) { return transition(state, signals, Map.of()); }
    private static GameTransition transition(LiarGameState state, List<GameSignal> signals, Map<ActorId, Integer> scores) { return new GameTransition(state, signals, scores, Optional.of(deadline(state)), false); }
    private static GameTransition unchanged(LiarGameState state) { return new GameTransition(state, List.of(), Map.of(), Optional.of(deadline(state)), false); }
    private static GameDeadline deadline(LiarGameState state) { return new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt()); }
    private static GameSignal phaseSignal(LiarGameState state) { return GameSignal.publicSignal("GAME_PHASE_CHANGED", Map.of("round", state.round(), "phase", state.phase(), "deadlineAt", state.deadlineAt())); }
    private static Map<String, Object> resultPayload(LiarGameState state) { var result = state.roundResult(); return Map.of("winner", result.winner(), "invalidated", result.invalidated(), "liarId", state.liarId(), "answer", state.word().answer(), "liarGuessedCorrectly", result.liarGuessedCorrectly()); }
    private static boolean matches(LiarGameState state, GameDeadline expected) { return expected.matches(state.sessionId(), state.round(), state.phaseVersion()) && expected.at().equals(state.deadlineAt()); }
    private static String hintText(GameAction action) { var value = action.data().get("hint"); if (!(value instanceof String hint) || hint.isBlank()) throw violation("GAME_HINT_INVALID"); return hint; }
    private static ActorId target(GameAction action) { var raw = action.data().get("targetActorId"); if (raw instanceof ActorId actorId) return actorId; if (raw instanceof String value && !value.isBlank()) return new ActorId(value); throw violation("GAME_TARGET_INVALID"); }
    private static boolean isValidHint(String hint, LiarWord word) { if (hint.matches(".*[\\r\\n\\u2028\\u2029].*") || sentenceEndingCount(hint) >= 2) return false; var normalized = TextNormalizer.normalize(hint); return !normalized.isEmpty() && !normalized.contains(TextNormalizer.normalize(word.answer())) && word.aliases().stream().map(TextNormalizer::normalize).filter(alias -> !alias.isEmpty()).noneMatch(normalized::contains); }
    private static long sentenceEndingCount(String hint) { return hint.codePoints().filter(point -> point == '.' || point == '!' || point == '?' || point == '。' || point == '！' || point == '？').count(); }
    private static ArrayList<ActorId> shuffledActorIds(List<GamePlayer> players, RandomGenerator random) { var result = new ArrayList<>(players.stream().map(GamePlayer::actorId).toList()); for (var index = result.size() - 1; index > 0; index--) { var swap = random.nextInt(index + 1); var current = result.get(index); result.set(index, result.get(swap)); result.set(swap, current); } return result; }
    private static Shuffle shuffle(Set<ActorId> ids, long randomState) { var result = new ArrayList<>(ids); var state = randomState; for (var index = result.size() - 1; index > 0; index--) { state = state * 6364136223846793005L + 1442695040888963407L; var swap = (int) Long.remainderUnsigned(state, index + 1L); var current = result.get(index); result.set(index, result.get(swap)); result.set(swap, current); } return new Shuffle(result, state); }
    private static boolean majority(LiarGameState state) { return state.discussionEndVotes().size() >= state.players().size() / 2 + 1; }
    private static boolean allEligibleVoted(LiarGameState state) { return activeIds(state).stream().allMatch(state.votes()::containsKey); }
    private static Set<ActorId> activeIds(LiarGameState state) { return state.players().stream().map(GamePlayer::actorId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)); }
    private static Map<ActorId, ActorId> withoutVote(Map<ActorId, ActorId> votes, ActorId actorId) { var copy = new HashMap<>(votes); copy.entrySet().removeIf(entry -> entry.getKey().equals(actorId) || entry.getValue().equals(actorId)); return copy; }
    private static Set<ActorId> withoutActor(Set<ActorId> values, ActorId actorId) { return values.stream().filter(id -> !id.equals(actorId)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)); }
    private static void requireActive(LiarGameState state, ActorId actorId) { if (!activeIds(state).contains(actorId)) throw violation("GAME_ACTION_NOT_ALLOWED"); }
    private static void validateRoster(List<GamePlayer> players) { if (players.size() < GameType.LIAR.minimumParticipants() || players.size() > GameType.LIAR.maximumParticipants()) throw violation("GAME_START_CONDITION_NOT_MET"); var ids = new HashSet<ActorId>(); if (!players.stream().map(GamePlayer::actorId).allMatch(ids::add)) throw violation("GAME_START_CONDITION_NOT_MET"); }
    private static void validateUniqueRoster(List<GamePlayer> players) { Objects.requireNonNull(players, "players"); if (players.size() > GameType.LIAR.maximumParticipants()) throw violation("GAME_START_CONDITION_NOT_MET"); var ids = new HashSet<ActorId>(); if (!players.stream().map(GamePlayer::actorId).allMatch(ids::add)) throw violation("GAME_START_CONDITION_NOT_MET"); }
    private static LiarGameState liarState(GameState state) { if (!(state instanceof LiarGameState liarState)) throw violation("GAME_STATE_INVALID"); return liarState; }
    private static GameRuleViolation violation(String code) { return new GameRuleViolation(code); }
    private record Shuffle(List<ActorId> ids, long randomState) { }
}
