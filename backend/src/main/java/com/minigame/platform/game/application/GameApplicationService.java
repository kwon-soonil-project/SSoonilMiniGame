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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(GameApplicationService.class);
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
    private final Set<StartRequest> startingRequests = ConcurrentHashMap.newKeySet();

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
        var requestUuid = canonicalRequestId(requestId);
        var startRequest = new StartRequest(roomId, actor.actorId(), requestUuid);
        if (!startingRequests.add(startRequest)) {
            return;
        }
        try {
            var token = rooms.withRoomValue(
                    roomId,
                    room -> room.prepareGameStart(actor.actorId(), requestId)
            ).value();
            if (token.isEmpty()) {
                return;
            }
            var expected = token.orElseThrow();
            requireSupportedStart(expected);
            var selected = selectContent(expected.settings(), new LinkedHashSet<>(expected.recentContentIds()));
            rooms.withRoomValue(roomId, room -> {
                Optional<Room.GameStartToken> current;
                try {
                    current = room.prepareGameStart(actor.actorId(), requestId);
                } catch (com.minigame.platform.room.domain.RoomRuleViolation exception) {
                    throw violation("GAME_START_STATE_CHANGED");
                }
                if (current.isEmpty()) {
                    return null;
                }
                if (!current.orElseThrow().equals(expected)) {
                    throw violation("GAME_START_STATE_CHANGED");
                }
                var sessionId = UUID.randomUUID();
                var gameSettings = gameSettings(expected.settings());
                var transition = modules.get(GameType.LIAR).start(new GameStartContext(
                        sessionId,
                        expected.activePlayers(),
                        gameSettings,
                        new ArrayList<GameContent>(selected),
                        clock.instant(),
                        random
                ));
                var runtime = new GameRuntime(
                        sessionId,
                        GameType.LIAR,
                        transition.state(),
                        expected.activePlayers(),
                        selected.stream().map(GameContent::id).toList()
                );
                runtime.applyScoreDeltas(transition.scoreDeltas());
                var prepared = prepareSchedule(roomId, transition.deadline());
                var persisted = false;
                try {
                    sessions.start(new GameSessionPort.StartGameSession(
                            sessionId,
                            roomId.value(),
                            GameType.LIAR,
                            settingsJson(gameSettings),
                            clock.instant()
                    ));
                    persisted = true;
                    List<RoomEvent> events;
                    try {
                        events = room.startGame(actor.actorId(), requestId, expected, runtime);
                    } catch (com.minigame.platform.room.domain.RoomRuleViolation exception) {
                        throw violation("GAME_START_STATE_CHANGED");
                    }
                    commitSchedule(roomId, prepared);
                    publishGameState(room, actor, requestId, events.getLast().sequence());
                    publishLobbyUpsert(room.snapshot(), actor, requestId);
                } catch (RuntimeException exception) {
                    prepared.cancel();
                    if (persisted) {
                        compensatePersistedStart(sessionId, exception);
                    }
                    throw exception;
                }
                return null;
            });
        } finally {
            startingRequests.remove(startRequest);
        }
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
        var gameAction = new GameAction(action, data);
        rooms.withRoomValue(roomId, room -> {
            if ("RETURN_TO_WAITING".equals(gameAction.type())) {
                var optionalRuntime = room.gameRuntime();
                if (optionalRuntime.isPresent()) {
                    var runtime = optionalRuntime.orElseThrow();
                    requireCurrentHost(room, actor.actorId());
                    if (!(runtime.state() instanceof LiarGameState liar) || liar.phase() != LiarPhase.GAME_RESULT) {
                        throw violation("GAME_ACTION_NOT_ALLOWED");
                    }
                }
                var events = room.finishGame(actor.actorId(), requestId);
                if (events.isEmpty()) {
                    return null;
                }
                commitSchedule(roomId, PreparedSchedule.none());
                publishFinishEvents(room, actor, requestId, events);
                publishLobbyUpsert(room.snapshot(), actor, requestId);
                return null;
            }
            var runtime = room.gameRuntime().orElseThrow(() -> violation("GAME_NOT_RUNNING"));
            if (runtime.hasProcessedRequest(actor.actorId(), requestUuid)) {
                return null;
            }
            if ("DISCUSSION_END_PROPOSE".equals(gameAction.type())) {
                requireCurrentHost(room, actor.actorId());
            }
            var module = modules.get(runtime.gameType());
            var transition = module.handle(
                    runtime.state(),
                    actor.actorId(),
                    gameAction,
                    clock.instant()
            );
            applyTransition(
                    room, actor, requestId, runtime, transition, clock.instant(),
                    Optional.of(requestUuid), List.of(), null, false
            );
            return null;
        });
    }

    public void expire(RoomId roomId, GameDeadline expected) {
        Objects.requireNonNull(expected, "expected");
        rooms.withRoomValue(roomId, room -> {
            var optionalRuntime = room.gameRuntime();
            if (optionalRuntime.isEmpty()) {
                return null;
            }
            var runtime = optionalRuntime.orElseThrow();
            if (!matches(runtime, expected) || clock.instant().isBefore(expected.at())) {
                return null;
            }
            GameTransition transition;
            List<ActorId> promotions = List.of();
            List<com.minigame.platform.game.domain.GamePlayer> synchronizedPlayers = null;
            boolean recordNextRound = false;
            if (runtime.state() instanceof LiarGameState liar && liar.phase() == LiarPhase.ROUND_RESULT) {
                if (liar.round() < liar.totalRounds()) {
                    promotions = room.previewSpectatorPromotions();
                }
                var players = room.activeGamePlayersAfterPromoting(promotions);
                synchronizedPlayers = players;
                transition = modules.get(runtime.gameType())
                        .synchronizePlayers(runtime.state(), players, clock.instant());
                if (transition.state() instanceof LiarGameState next && next.round() > liar.round()) {
                    recordNextRound = true;
                }
            } else {
                transition = modules.get(runtime.gameType())
                        .expire(runtime.state(), expected, clock.instant());
            }
            if (transition.completed()) {
                var requestId = UUID.randomUUID().toString();
                var events = room.finishGameOnExpiry();
                commitSchedule(roomId, PreparedSchedule.none());
                publishFinishEvents(room, SYSTEM, requestId, events);
                publishLobbyUpsert(room.snapshot(), SYSTEM, requestId);
                return null;
            }
            if (transition.state() == runtime.state()
                    && transition.scoreDeltas().isEmpty()
                    && transition.signals().isEmpty()) {
                return null;
            }
            applyTransition(
                    room, SYSTEM, UUID.randomUUID().toString(), runtime, transition, clock.instant(),
                    Optional.empty(), promotions, synchronizedPlayers, recordNextRound
            );
            return null;
        });
    }

    /** Called by RoomApplicationService while it already owns this room's lock. */
    public List<GameSignal> participantLeft(Room room, ActorId actorId, Instant now) {
        var optionalRuntime = room.gameRuntime();
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
        applyTransition(
                room, actor, requestId, runtime, transition, now,
                Optional.empty(), List.of(), null, false
        );
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

    public void roomClosed(Room room, Instant now) {
        var runtime = room.gameRuntime().orElse(null);
        if (runtime != null) {
            sessions.interrupt(runtime.sessionId(), now);
        }
        cancelRoomScheduleStrict(room.snapshot().id());
    }

    private void applyTransition(
            Room room,
            ActorPrincipal actor,
            String requestId,
            GameRuntime runtime,
            GameTransition transition,
            Instant now,
            Optional<UUID> processedRequest,
            List<ActorId> promotions,
            List<com.minigame.platform.game.domain.GamePlayer> synchronizedPlayers,
            boolean recordNextRound
    ) {
        var previous = runtime.state();
        var prepared = prepareSchedule(room.snapshot().id(), transition.deadline());
        try {
            if (enteredGameResult(previous, transition.state())) {
                completeSession(runtime, transition.scoreDeltas(), synchronizedPlayers, now);
            }
        } catch (RuntimeException exception) {
            prepared.cancel();
            throw exception;
        }
        if (synchronizedPlayers != null) {
            runtime.synchronizePlayers(synchronizedPlayers);
            if (recordNextRound) {
                runtime.recordRoundParticipation(synchronizedPlayers);
            }
        }
        runtime.replaceState(transition.state());
        runtime.applyScoreDeltas(transition.scoreDeltas());
        processedRequest.ifPresent(request -> runtime.markRequestProcessed(actor.actorId(), request));
        var events = promotions.isEmpty()
                ? room.replaceGame(runtime)
                : room.replaceGameAndPromote(runtime, promotions);
        commitSchedule(room.snapshot().id(), prepared);
        publishRoomEvents(room, actor, requestId, events);
        publishGameState(room, actor, requestId, events.getLast().sequence());
    }

    private void completeSession(
            GameRuntime runtime,
            Map<ActorId, Integer> deltas,
            List<com.minigame.platform.game.domain.GamePlayer> prospectivePlayers,
            Instant now
    ) {
        var scores = new LinkedHashMap<>(runtime.scores());
        deltas.forEach((actorId, delta) -> scores.merge(actorId, delta, Integer::sum));
        var nicknames = new LinkedHashMap<>(runtime.playerNicknames());
        var rounds = new LinkedHashMap<>(runtime.roundsPlayed());
        if (prospectivePlayers != null) {
            for (var player : prospectivePlayers) {
                scores.putIfAbsent(player.actorId(), 0);
                nicknames.putIfAbsent(player.actorId(), player.nickname());
                rounds.putIfAbsent(player.actorId(), 0);
            }
        }
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
        var roomSnapshot = room.snapshot();
        var runtime = roomSnapshot.gameRuntime().orElseThrow();
        var publicState = snapshot(runtime, actor.actorId()).publicState();
        safePublish(() -> publisher.publishPublic(EventEnvelope.create(
                requestId, roomSnapshot.id(), actor, "GAME_STATE_CHANGED", sequence, clock,
                Map.of("game", publicState)
        )));
        for (var participant : roomSnapshot.participants()) {
            var privateState = snapshot(runtime, participant.actorId()).privateState();
            if (privateState != null) {
                safePublish(() -> publisher.publishPrivate(participant.actorId().value(), EventEnvelope.create(
                        requestId, roomSnapshot.id(), actor, "GAME_PRIVATE_STATE_CHANGED", sequence, clock,
                        Map.of("game", privateState)
                )));
            }
        }
    }

    private void publishEmptyGame(RoomId roomId, ActorPrincipal actor, String requestId, long sequence) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("game", null);
        safePublish(() -> publisher.publishPublic(EventEnvelope.create(
                requestId, roomId, actor, "GAME_STATE_CHANGED", sequence, clock, payload
        )));
    }

    private void publishFinishEvents(
            Room room,
            ActorPrincipal actor,
            String requestId,
            List<RoomEvent> events
    ) {
        publishRoomEvents(room, actor, requestId, events);
        var gameEvent = events.stream()
                .filter(RoomEvent.GameStateChanged.class::isInstance)
                .map(RoomEvent::sequence)
                .findFirst().orElseThrow();
        publishEmptyGame(room.snapshot().id(), actor, requestId, gameEvent);
    }

    private void publishRoomEvents(
            Room room,
            ActorPrincipal actor,
            String requestId,
            List<RoomEvent> events
    ) {
        for (var event : events) {
            if (event instanceof RoomEvent.PlayerSpectatorChanged changed) {
                safePublish(() -> publisher.publishPublic(EventEnvelope.create(
                        requestId,
                        room.snapshot().id(),
                        actor,
                        "PLAYER_SPECTATOR_CHANGED",
                        changed.sequence(),
                        clock,
                        Map.of("actorId", changed.actorId().value(), "spectator", changed.spectator())
                )));
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
        safePublish(() -> publisher.publishLobby(EventEnvelope.create(
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
        )));
    }

    private GameSnapshotView snapshot(GameRuntime.Snapshot runtime, ActorId viewer) {
        var projected = modules.get(runtime.gameType()).project(runtime.state(), viewer);
        var publicState = publicState(
                projected.publicState(), runtime.scores(), runtime.playerNicknames(), runtime.roundsPlayed()
        );
        var privateState = projected.privateState().map(this::privateState).orElse(null);
        return new GameSnapshotView(publicState, privateState);
    }

    private void safePublish(Runnable publication) {
        try {
            publication.run();
        } catch (RuntimeException exception) {
            log.warn("Best-effort room event publication failed; clients recover from the room snapshot", exception);
        }
    }

    private Object publicState(
            GameProjection.View view,
            Map<ActorId, Integer> scores,
            Map<ActorId, String> nicknames,
            Map<ActorId, Integer> roundsPlayed
    ) {
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
        result.put("hintStatuses", state.hintStatuses().stream().map(status -> Map.of(
                "playerId", status.playerId().value(), "status", status.status()
        )).toList());
        result.put("submittedPlayerIds", state.submittedPlayerIds().stream()
                .map(ActorId::value).sorted().toList());
        if (state.phase() == LiarPhase.REVOTING) {
            result.put("revoteCandidates", state.revoteCandidates().stream()
                    .map(ActorId::value).sorted().toList());
        }
        result.put("scores", stringScores(scores));
        if (state.roundResult() != null) {
            result.put("liarId", state.liarId().value());
            result.put("answer", state.answer());
            var round = new LinkedHashMap<String, Object>();
            round.put("winner", state.roundResult().winner());
            round.put("invalidated", state.roundResult().invalidated());
            if (state.roundResult().accusedId() != null) {
                round.put("accusedId", state.roundResult().accusedId().value());
            }
            round.put("liarGuessedCorrectly", state.roundResult().liarGuessedCorrectly());
            result.put("roundResult", round);
        }
        if (state.phase() == LiarPhase.GAME_RESULT) {
            result.put("finalScores", scores.entrySet().stream()
                    .sorted(Map.Entry.<ActorId, Integer>comparingByValue().reversed()
                            .thenComparing(entry -> entry.getKey().value()))
                    .map(entry -> Map.of(
                            "actorId", entry.getKey().value(),
                            "nickname", nicknames.getOrDefault(entry.getKey(), entry.getKey().value()),
                            "score", entry.getValue(),
                            "rank", 1 + scores.values().stream().filter(score -> score > entry.getValue()).count(),
                            "roundsPlayed", roundsPlayed.getOrDefault(entry.getKey(), 0)
                    ))
                    .toList());
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

    private PreparedSchedule prepareSchedule(RoomId roomId, Optional<GameDeadline> deadline) {
        if (deadline.isEmpty()) {
            return PreparedSchedule.none();
        }
        var expected = deadline.orElseThrow();
        var cancellation = scheduler.schedule(roomId, expected, () -> expire(roomId, expected));
        return new PreparedSchedule(Optional.of(new ScheduledDeadline(expected, cancellation)));
    }

    private synchronized void commitSchedule(RoomId roomId, PreparedSchedule prepared) {
        var replacement = prepared.scheduled().orElse(null);
        var previous = replacement == null ? schedules.remove(roomId) : schedules.put(roomId, replacement);
        if (previous != null && previous != replacement) {
            cancelQuietly(previous.cancellation());
        }
    }

    private synchronized void cancelRoomScheduleStrict(RoomId roomId) {
        var current = schedules.get(roomId);
        if (current == null) {
            return;
        }
        current.cancellation().cancel();
        schedules.remove(roomId, current);
    }

    private void cancelQuietly(GameSchedulePort.Cancellation cancellation) {
        try {
            cancellation.cancel();
        } catch (RuntimeException exception) {
            log.warn("Deadline cancellation failed after replacement was installed", exception);
        }
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

    private void requireSupportedStart(Room.GameStartToken token) {
        var gameType = token.settings().gameType();
        if (gameType != GameType.LIAR || modules.find(gameType).isEmpty()) {
            throw violation("GAME_TYPE_UNSUPPORTED");
        }
    }

    private void compensatePersistedStart(UUID sessionId, RuntimeException original) {
        try {
            if (!sessions.interrupt(sessionId, clock.instant())) {
                original.addSuppressed(new IllegalStateException(
                        "Persisted game session could not be interrupted: " + sessionId
                ));
            }
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
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

    private static GameSettings gameSettings(com.minigame.platform.room.domain.RoomSettings settings) {
        return new GameSettings(
                settings.rounds(),
                settings.actionSeconds(),
                settings.discussionSeconds(),
                settings.categoryPack()
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

    private record PreparedSchedule(Optional<ScheduledDeadline> scheduled) {
        private PreparedSchedule {
            Objects.requireNonNull(scheduled, "scheduled");
        }

        static PreparedSchedule none() {
            return new PreparedSchedule(Optional.empty());
        }

        void cancel() {
            scheduled.ifPresent(value -> {
                try {
                    value.cancellation().cancel();
                } catch (RuntimeException exception) {
                    log.warn("Prepared deadline cancellation failed during rollback", exception);
                }
            });
        }
    }

    private record StartRequest(RoomId roomId, ActorId actorId, UUID requestId) {
    }
}
