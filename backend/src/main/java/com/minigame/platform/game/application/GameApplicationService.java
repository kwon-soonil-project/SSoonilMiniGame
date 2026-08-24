package com.minigame.platform.game.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.game.domain.GameAction;
import com.minigame.platform.game.domain.GameContent;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameProjection;
import com.minigame.platform.game.domain.GameRuleViolation;
import com.minigame.platform.game.domain.GameRuntime;
import com.minigame.platform.game.domain.GameSettings;
import com.minigame.platform.game.domain.GameSignal;
import com.minigame.platform.game.domain.GameStartContext;
import com.minigame.platform.game.domain.GameTransition;
import com.minigame.platform.game.domain.liar.LiarGameState;
import com.minigame.platform.game.domain.liar.LiarPhase;
import com.minigame.platform.game.domain.liar.LiarProjection;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomStatus;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

@Service
public class GameApplicationService {
    private static final ActorPrincipal SYSTEM = ActorPrincipal.guest(new ActorId("game-system"), "시스템");

    private final ActiveRoomRepository rooms;
    private final GameModuleRegistry modules;
    private final LiarContentPort content;
    private final GameSessionPort sessions;
    private final GameSchedulePort scheduler;
    private final RoomEventPublisher publisher;
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<RoomId, ScheduledDeadline> schedules = new ConcurrentHashMap<>();

    @Autowired
    public GameApplicationService(
            ActiveRoomRepository rooms,
            GameModuleRegistry modules,
            LiarContentPort content,
            GameSessionPort sessions,
            GameSchedulePort scheduler,
            RoomEventPublisher publisher,
            Clock clock
    ) {
        this(rooms, modules, content, sessions, scheduler, publisher, clock, new java.security.SecureRandom());
    }

    public GameApplicationService(
            ActiveRoomRepository rooms,
            GameModuleRegistry modules,
            LiarContentPort content,
            GameSessionPort sessions,
            GameSchedulePort scheduler,
            RoomEventPublisher publisher,
            Clock clock,
            RandomGenerator random
    ) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.content = Objects.requireNonNull(content, "content");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public void start(ActorPrincipal actor, RoomId roomId, String requestId) {
        Objects.requireNonNull(actor, "actor");
        var before = rooms.findById(roomId).orElseThrow(() -> violation("ROOM_NOT_FOUND"));
        var settings = before.settings();
        var selected = selectContent(settings, new LinkedHashSet<>(before.recentContentIds()));
        rooms.withRoomValue(roomId, room -> {
            if (!room.validateGameStart(actor.actorId(), requestId)) {
                return null;
            }
            if (room.snapshot().settings().gameType() != GameType.LIAR
                    || selected.size() != room.snapshot().settings().rounds()) {
                throw violation("GAME_CONTENT_UNAVAILABLE");
            }
            var sessionId = UUID.randomUUID();
            var players = room.activeGamePlayers();
            var gameSettings = gameSettings(room.snapshot());
            var transition = modules.get(GameType.LIAR).start(new GameStartContext(
                    sessionId,
                    players,
                    gameSettings,
                    new ArrayList<GameContent>(selected),
                    clock.instant(),
                    random
            ));
            var runtime = new GameRuntime(
                    sessionId,
                    GameType.LIAR,
                    transition.state(),
                    players,
                    selected.stream().map(GameContent::id).toList()
            );
            runtime.applyScoreDeltas(transition.scoreDeltas());
            var persistedId = sessions.start(new GameSessionPort.StartGameSession(
                    sessionId,
                    roomId.value(),
                    GameType.LIAR,
                    settingsJson(gameSettings),
                    clock.instant()
            ));
            if (!sessionId.equals(persistedId)) {
                throw new IllegalStateException("Game session persistence changed the session ID");
            }
            var events = room.startGame(actor.actorId(), requestId, runtime);
            if (events.isEmpty()) {
                return null;
            }
            publishGameState(room, actor, requestId, events.getFirst().sequence());
            replaceSchedule(roomId, transition.deadline());
            publishLobbyUpsert(room.snapshot(), actor, requestId);
            return null;
        });
    }

    public void act(
            ActorPrincipal actor,
            RoomId roomId,
            String requestId,
            String action,
            Map<String, Object> data
    ) {
        Objects.requireNonNull(actor, "actor");
        var requestUuid = canonicalRequestId(requestId);
        rooms.withRoomValue(roomId, room -> {
            var runtime = room.snapshot().gameRuntime().orElseThrow(() -> violation("GAME_NOT_RUNNING"));
            if (runtime.hasProcessedRequest(requestUuid)) {
                return null;
            }
            if ("RETURN_TO_WAITING".equals(action)) {
                requireCurrentHost(room, actor.actorId());
                if (!(runtime.state() instanceof LiarGameState liar) || liar.phase() != LiarPhase.GAME_RESULT) {
                    throw violation("GAME_ACTION_NOT_ALLOWED");
                }
                runtime.markRequestProcessed(requestUuid);
                var sequence = room.finishGame().getFirst().sequence();
                publishEmptyGame(roomId, actor, requestId, sequence);
                replaceSchedule(roomId, Optional.empty());
                publishLobbyUpsert(room.snapshot(), actor, requestId);
                return null;
            }
            if ("DISCUSSION_END_PROPOSE".equals(action)) {
                requireCurrentHost(room, actor.actorId());
            }
            var module = modules.get(runtime.gameType());
            var transition = module.handle(
                    runtime.state(),
                    actor.actorId(),
                    new GameAction(action, data),
                    clock.instant()
            );
            runtime.markRequestProcessed(requestUuid);
            applyTransition(room, actor, requestId, runtime, transition, clock.instant());
            return null;
        });
    }

    public void expire(RoomId roomId, GameDeadline expected) {
        Objects.requireNonNull(expected, "expected");
        rooms.withRoomValue(roomId, room -> {
            var optionalRuntime = room.snapshot().gameRuntime();
            if (optionalRuntime.isEmpty()) {
                return null;
            }
            var runtime = optionalRuntime.orElseThrow();
            if (!matches(runtime, expected) || clock.instant().isBefore(expected.at())) {
                return null;
            }
            GameTransition transition;
            if (runtime.state() instanceof LiarGameState liar && liar.phase() == LiarPhase.ROUND_RESULT) {
                if (liar.round() < liar.totalRounds()) {
                    publishRoomEvents(room, SYSTEM, UUID.randomUUID().toString(), room.promoteSpectators());
                }
                var players = room.activeGamePlayers();
                runtime.synchronizePlayers(players);
                transition = modules.get(runtime.gameType())
                        .synchronizePlayers(runtime.state(), players, clock.instant());
                if (transition.state() instanceof LiarGameState next && next.round() > liar.round()) {
                    runtime.recordRoundParticipation(players);
                }
            } else {
                transition = modules.get(runtime.gameType())
                        .expire(runtime.state(), expected, clock.instant());
            }
            if (transition.completed()) {
                var requestId = UUID.randomUUID().toString();
                var sequence = room.finishGame().getFirst().sequence();
                publishEmptyGame(roomId, SYSTEM, requestId, sequence);
                replaceSchedule(roomId, Optional.empty());
                publishLobbyUpsert(room.snapshot(), SYSTEM, requestId);
                return null;
            }
            if (transition.state() == runtime.state()
                    && transition.scoreDeltas().isEmpty()
                    && transition.signals().isEmpty()) {
                return null;
            }
            applyTransition(room, SYSTEM, UUID.randomUUID().toString(), runtime, transition, clock.instant());
            return null;
        });
    }

    /** Called by RoomApplicationService while it already owns this room's lock. */
    public List<GameSignal> participantLeft(Room room, ActorId actorId, Instant now) {
        var optionalRuntime = room.snapshot().gameRuntime();
        if (optionalRuntime.isEmpty()) {
            return List.of();
        }
        var runtime = optionalRuntime.orElseThrow();
        var transition = modules.get(runtime.gameType()).removePlayer(runtime.state(), actorId, now);
        if (transition.state() == runtime.state()
                && transition.scoreDeltas().isEmpty()
                && transition.signals().isEmpty()) {
            return List.of();
        }
        var requestId = UUID.randomUUID().toString();
        var actor = principalFor(room.snapshot(), actorId);
        applyTransition(room, actor, requestId, runtime, transition, now);
        return transition.signals();
    }

    public boolean canStart(Room.Snapshot room) {
        return room.status() == RoomStatus.WAITING
                && room.participantsReadyToStart()
                && room.settings().gameType() == GameType.LIAR
                && modules.find(GameType.LIAR).isPresent()
                && content.available(room.settings().categoryPack(), Set.of(), room.settings().rounds());
    }

    public Optional<GameSnapshotView> snapshot(Room.Snapshot room, ActorId viewer) {
        return room.gameRuntime().map(runtime -> snapshot(runtime, viewer));
    }

    public void roomClosed(RoomId roomId) {
        replaceSchedule(roomId, Optional.empty());
    }

    private void applyTransition(
            Room room,
            ActorPrincipal actor,
            String requestId,
            GameRuntime runtime,
            GameTransition transition,
            Instant now
    ) {
        var previous = runtime.state();
        runtime.replaceState(transition.state());
        runtime.applyScoreDeltas(transition.scoreDeltas());
        var sequence = room.replaceGame(runtime).getFirst().sequence();
        if (enteredGameResult(previous, transition.state())) {
            completeSession(runtime, now);
        }
        publishGameState(room, actor, requestId, sequence);
        replaceSchedule(room.snapshot().id(), transition.deadline());
    }

    private void completeSession(GameRuntime runtime, Instant now) {
        var scores = runtime.scores();
        var nicknames = runtime.playerNicknames();
        var rounds = runtime.roundsPlayed();
        var results = scores.entrySet().stream()
                .sorted(Map.Entry.<ActorId, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().value()))
                .map(entry -> new GameSessionPort.GameParticipantResult(
                        uuidActorId(entry.getKey()),
                        nicknames.get(entry.getKey()),
                        entry.getValue(),
                        1 + (int) scores.values().stream().filter(score -> score > entry.getValue()).count(),
                        rounds.getOrDefault(entry.getKey(), 0)
                ))
                .toList();
        sessions.complete(runtime.sessionId(), results, now);
    }

    private void publishGameState(Room room, ActorPrincipal actor, String requestId, long sequence) {
        var runtime = room.snapshot().gameRuntime().orElseThrow();
        var publicState = snapshot(runtime, actor.actorId()).publicState();
        publisher.publishPublic(EventEnvelope.create(
                requestId, room.snapshot().id(), actor, "GAME_STATE_CHANGED", sequence, clock,
                Map.of("game", publicState)
        ));
        for (var participant : room.snapshot().participants()) {
            var privateState = snapshot(runtime, participant.actorId()).privateState();
            if (privateState != null) {
                publisher.publishPrivate(participant.actorId().value(), EventEnvelope.create(
                        requestId, room.snapshot().id(), actor, "GAME_PRIVATE_STATE_CHANGED", sequence, clock,
                        Map.of("game", privateState)
                ));
            }
        }
    }

    private void publishEmptyGame(RoomId roomId, ActorPrincipal actor, String requestId, long sequence) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("game", null);
        publisher.publishPublic(EventEnvelope.create(
                requestId, roomId, actor, "GAME_STATE_CHANGED", sequence, clock, payload
        ));
    }

    private void publishRoomEvents(
            Room room,
            ActorPrincipal actor,
            String requestId,
            List<RoomEvent> events
    ) {
        for (var event : events) {
            if (event instanceof RoomEvent.PlayerSpectatorChanged changed) {
                publisher.publishPublic(EventEnvelope.create(
                        requestId,
                        room.snapshot().id(),
                        actor,
                        "PLAYER_SPECTATOR_CHANGED",
                        changed.sequence(),
                        clock,
                        Map.of("actorId", changed.actorId().value(), "spectator", changed.spectator())
                ));
            }
        }
    }

    private void publishLobbyUpsert(Room.Snapshot room, ActorPrincipal actor, String requestId) {
        if (room.visibility() != com.minigame.platform.room.domain.Visibility.PUBLIC
                || room.status() == RoomStatus.CLOSED) {
            return;
        }
        var hostNickname = room.participants().stream()
                .filter(participant -> participant.actorId().equals(room.hostId()))
                .map(participant -> participant.nickname())
                .findFirst().orElse("");
        publisher.publishLobby(EventEnvelope.create(
                requestId,
                room.id(),
                actor,
                "LOBBY_ROOM_UPSERT",
                room.sequence(),
                clock,
                Map.of(
                        "roomId", room.id().value().toString(),
                        "code", room.code().value(),
                        "title", room.title(),
                        "gameType", room.settings().gameType(),
                        "status", room.status(),
                        "passwordProtected", room.passwordProtected(),
                        "participantCount", room.participants().stream().filter(p -> !p.spectator()).count(),
                        "maxParticipants", room.settings().maxParticipants(),
                        "hostNickname", hostNickname,
                        "sequence", room.sequence()
                )
        ));
    }

    private GameSnapshotView snapshot(GameRuntime runtime, ActorId viewer) {
        var projected = modules.get(runtime.gameType()).project(runtime.state(), viewer);
        var publicState = publicState(projected.publicState(), runtime.scores());
        var privateState = projected.privateState().map(this::privateState).orElse(null);
        return new GameSnapshotView(publicState, privateState);
    }

    private Object publicState(GameProjection.View view, Map<ActorId, Integer> scores) {
        if (!(view instanceof LiarProjection.PublicState state)) {
            throw new IllegalArgumentException("Unsupported game projection");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("gameType", "LIAR");
        result.put("round", state.round());
        result.put("phase", state.phase().name());
        result.put("deadlineAt", state.deadlineAt());
        if (state.currentHinter() != null) {
            result.put("currentHinter", state.currentHinter().value());
        }
        result.put("hints", state.hints().stream().map(hint -> Map.of(
                "playerId", hint.playerId().value(), "text", hint.text()
        )).toList());
        result.put("submittedPlayerIds", state.submittedPlayerIds().stream()
                .map(ActorId::value).sorted().toList());
        result.put("scores", stringScores(scores));
        if (state.roundResult() != null) {
            var round = new LinkedHashMap<String, Object>();
            round.put("winner", state.roundResult().winner());
            round.put("invalidated", state.roundResult().invalidated());
            if (state.roundResult().accusedId() != null) {
                round.put("accusedId", state.roundResult().accusedId().value());
            }
            round.put("liarGuessedCorrectly", state.roundResult().liarGuessedCorrectly());
            result.put("roundResult", round);
        }
        return Map.copyOf(result);
    }

    private Object privateState(GameProjection.View view) {
        if (!(view instanceof LiarProjection.PrivateState state)) {
            throw new IllegalArgumentException("Unsupported private game projection");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("role", state.role());
        result.put("category", state.category());
        if (state.word() != null) {
            result.put("word", state.word());
        }
        result.put("hintSubmitted", state.hintSubmitted());
        result.put("voteSubmitted", state.voteSubmitted());
        return Map.copyOf(result);
    }

    private synchronized void replaceSchedule(RoomId roomId, Optional<GameDeadline> deadline) {
        var previous = schedules.remove(roomId);
        if (previous != null) {
            previous.cancellation().cancel();
        }
        deadline.ifPresent(expected -> {
            var cancellation = scheduler.schedule(roomId, expected, () -> expire(roomId, expected));
            schedules.put(roomId, new ScheduledDeadline(expected, cancellation));
        });
    }

    private List<? extends GameContent> selectContent(
            com.minigame.platform.room.domain.RoomSettings settings,
            Set<UUID> recent
    ) {
        var selected = content.select(settings.categoryPack(), recent, settings.rounds());
        if (selected.size() < settings.rounds() && !recent.isEmpty()) {
            selected = content.select(settings.categoryPack(), Set.of(), settings.rounds());
        }
        if (selected.size() < settings.rounds()) {
            throw violation("GAME_CONTENT_UNAVAILABLE");
        }
        return selected;
    }

    private static boolean matches(GameRuntime runtime, GameDeadline expected) {
        return runtime.state() instanceof LiarGameState state
                && expected.matches(state.sessionId(), state.round(), state.phaseVersion())
                && expected.at().equals(state.deadlineAt());
    }

    private static boolean enteredGameResult(Object previous, Object next) {
        return previous instanceof LiarGameState before
                && next instanceof LiarGameState after
                && before.phase() != LiarPhase.GAME_RESULT
                && after.phase() == LiarPhase.GAME_RESULT;
    }

    private static void requireCurrentHost(Room room, ActorId actorId) {
        if (!room.snapshot().hostId().equals(actorId)) {
            throw violation("GAME_HOST_REQUIRED");
        }
    }

    private static GameSettings gameSettings(Room.Snapshot room) {
        return new GameSettings(
                room.settings().rounds(),
                room.settings().actionSeconds(),
                room.settings().discussionSeconds(),
                room.settings().categoryPack()
        );
    }

    private static String settingsJson(GameSettings settings) {
        return "{\"rounds\":%d,\"actionSeconds\":%d,\"discussionSeconds\":%d,\"categoryPack\":\"%s\"}"
                .formatted(
                        settings.rounds(),
                        settings.actionSeconds(),
                        settings.discussionSeconds(),
                        settings.categoryPack().replace("\\", "\\\\").replace("\"", "\\\"")
                );
    }

    private static UUID canonicalRequestId(String requestId) {
        try {
            var parsed = UUID.fromString(requestId);
            if (!parsed.toString().equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw violation("GAME_REQUEST_ID_INVALID");
        }
    }

    private static Map<String, Integer> stringScores(Map<ActorId, Integer> scores) {
        var result = new LinkedHashMap<String, Integer>();
        scores.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> result.put(entry.getKey().value(), entry.getValue()));
        return Map.copyOf(result);
    }

    private static UUID uuidActorId(ActorId actorId) {
        var value = actorId.value();
        if (value.startsWith("guest:")) {
            value = value.substring("guest:".length());
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static ActorPrincipal principalFor(Room.Snapshot room, ActorId actorId) {
        var nickname = room.participants().stream()
                .filter(participant -> participant.actorId().equals(actorId))
                .map(participant -> participant.nickname())
                .findFirst().orElse("참가자");
        return ActorPrincipal.guest(actorId, nickname);
    }

    private static GameRuleViolation violation(String code) {
        return new GameRuleViolation(code);
    }

    public record GameSnapshotView(Object publicState, Object privateState) {
        public GameSnapshotView {
            Objects.requireNonNull(publicState, "publicState");
        }
    }

    private record ScheduledDeadline(GameDeadline deadline, GameSchedulePort.Cancellation cancellation) {
    }
}
