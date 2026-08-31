package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.game.domain.GamePlayer;
import com.minigame.platform.game.domain.GameRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class Room {
    private static final int REMEMBERED_REQUEST_LIMIT = 1_024;
    private static final int RECENT_CONTENT_LIMIT = 20;

    private final RoomId id;
    private final RoomCode code;
    private final String title;
    private final Visibility visibility;
    private final Map<ActorId, Participant> participants = new LinkedHashMap<>();
    private final Set<ProcessedRequest> processedRequests = new HashSet<>();
    private final ArrayDeque<ProcessedRequest> processedRequestOrder = new ArrayDeque<>();
    private final ArrayDeque<UUID> recentContentIds = new ArrayDeque<>();
    private RoomSettings settings;
    private RoomStatus status;
    private ActorId hostId;
    private long sequence;
    private long nextJoinedOrder;
    private GameRuntime gameRuntime;
    private boolean passwordProtected;

    private Room(
        RoomId id,
        RoomCode code,
        String title,
        Visibility visibility,
        RoomSettings settings,
        ActorId hostId,
        String hostNickname
    ) {
        this.id = Objects.requireNonNull(id, "roomId");
        this.code = Objects.requireNonNull(code, "roomCode");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title");
        }
        this.title = title.strip();
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.status = RoomStatus.WAITING;
        participants.put(hostId, new Participant(hostId, hostNickname, false, false, nextJoinedOrder++));
    }

    public static Room create(
        RoomId id,
        RoomCode code,
        String title,
        Visibility visibility,
        RoomSettings settings,
        ActorId hostId,
        String hostNickname
    ) {
        return new Room(id, code, title, visibility, settings, hostId, hostNickname);
    }

    public void markPasswordProtected() {
        if (sequence != 0 || participants.size() != 1) {
            throw new RoomRuleViolation("ROOM_PASSWORD_STATE_LOCKED");
        }
        passwordProtected = true;
    }

    public void rollbackPasswordProtection() {
        if (sequence != 0) {
            throw new RoomRuleViolation("ROOM_PASSWORD_STATE_LOCKED");
        }
        passwordProtected = false;
    }

    public List<RoomEvent> join(ActorId actorId, String nickname, boolean spectator, String requestId) {
        var request = processedRequest(actorId, CommandType.JOIN, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireOpen();
        if (participants.containsKey(actorId)) {
            throw new RoomRuleViolation("ROOM_PARTICIPANT_ALREADY_JOINED");
        }
        if (!spectator && activeParticipantCount() >= settings.maxParticipants()) {
            throw new RoomRuleViolation("ROOM_FULL");
        }
        var actualSpectator = spectator || status == RoomStatus.PLAYING;
        var participant = new Participant(actorId, nickname, false, actualSpectator, nextJoinedOrder++);
        participants.put(actorId, participant);
        if (!actualSpectator) {
            resetNonHostReadiness();
        }
        return complete(request, List.of(new RoomEvent.ParticipantJoined(
            nextSequence(), participant, participantsReadyToStart()
        )));
    }

    public List<RoomEvent> changeReady(ActorId actorId, boolean ready, String requestId) {
        var request = processedRequest(actorId, CommandType.CHANGE_READY, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        if (actorId.equals(hostId)) {
            throw new RoomRuleViolation("ROOM_HOST_CANNOT_READY");
        }
        if (status != RoomStatus.WAITING) {
            throw new RoomRuleViolation("ROOM_READY_LOCKED");
        }
        var participant = requireParticipant(actorId);
        if (participant.spectator()) {
            throw new RoomRuleViolation("ROOM_SPECTATOR_CANNOT_READY");
        }
        participants.put(actorId, participant.withReady(ready));
        return complete(request, List.of(new RoomEvent.ReadyChanged(
            nextSequence(), actorId, ready, participantsReadyToStart()
        )));
    }

    public List<RoomEvent> updateSettings(ActorId actorId, RoomSettings next, String requestId) {
        var request = processedRequest(actorId, CommandType.UPDATE_SETTINGS, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireHost(actorId);
        if (status != RoomStatus.WAITING) {
            throw new RoomRuleViolation("ROOM_SETTINGS_LOCKED");
        }
        Objects.requireNonNull(next, "settings");
        if (next.maxParticipants() < activeParticipantCount()) {
            throw new RoomRuleViolation("ROOM_MAX_PLAYERS_TOO_SMALL");
        }
        settings = next;
        resetNonHostReadiness();
        return complete(request, List.of(new RoomEvent.SettingsUpdated(
            nextSequence(), next, participantsReadyToStart()
        )));
    }

    public List<RoomEvent> transferHost(ActorId actorId, ActorId nextHostId, String requestId) {
        var request = processedRequest(actorId, CommandType.TRANSFER_HOST, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireOpen();
        requireHost(actorId);
        var nextHost = requireParticipant(nextHostId);
        if (nextHost.spectator()) {
            throw new RoomRuleViolation("ROOM_HOST_MUST_BE_ACTIVE");
        }
        if (hostId.equals(nextHostId)) {
            throw new RoomRuleViolation("ROOM_HOST_UNCHANGED");
        }
        var previousHostId = hostId;
        hostId = nextHostId;
        clearReadiness(nextHostId);
        return complete(
            request,
            List.of(new RoomEvent.HostTransferred(
                nextSequence(), previousHostId, nextHostId, participantsReadyToStart()
            ))
        );
    }

    public List<RoomEvent> leave(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.LEAVE, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireOpen();
        requireParticipant(actorId);
        participants.remove(actorId);
        var events = new ArrayList<RoomEvent>();
        if (participants.isEmpty()) {
            events.add(new RoomEvent.ParticipantLeft(nextSequence(), actorId, participantsReadyToStart()));
            status = RoomStatus.CLOSED;
            events.add(new RoomEvent.RoomClosed(nextSequence()));
            return complete(request, events);
        }
        if (hostId.equals(actorId)) {
            var replacement = participants.values().stream()
                .filter(participant -> !participant.spectator())
                .findFirst();
            if (replacement.isEmpty()) {
                events.add(new RoomEvent.ParticipantLeft(nextSequence(), actorId, participantsReadyToStart()));
                status = RoomStatus.CLOSED;
                events.add(new RoomEvent.RoomClosed(nextSequence()));
                return complete(request, events);
            }
            hostId = replacement.orElseThrow().actorId();
            clearReadiness(hostId);
            events.add(new RoomEvent.ParticipantLeft(nextSequence(), actorId, participantsReadyToStart()));
            events.add(new RoomEvent.HostTransferred(nextSequence(), actorId, hostId, participantsReadyToStart()));
            return complete(request, events);
        }
        events.add(new RoomEvent.ParticipantLeft(nextSequence(), actorId, participantsReadyToStart()));
        return complete(request, events);
    }

    /** Read-only preflight used under the room lock before fallible leave/close orchestration. */
    public Optional<Boolean> prepareLeave(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.LEAVE, requestId);
        if (processedRequests.contains(request)) {
            return Optional.empty();
        }
        requireOpen();
        requireParticipant(actorId);
        if (participants.size() == 1) {
            return Optional.of(true);
        }
        if (!hostId.equals(actorId)) {
            return Optional.of(false);
        }
        return Optional.of(participants.values().stream()
                .filter(participant -> !participant.actorId().equals(actorId))
                .noneMatch(participant -> !participant.spectator()));
    }

    public List<RoomEvent> acceptChat(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.CHAT_SEND, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireOpen();
        requireParticipant(actorId);
        return complete(request, List.of(new RoomEvent.ChatAccepted(nextSequence())));
    }

    public Optional<GameStartToken> prepareGameStart(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.GAME_START, requestId);
        if (processedRequests.contains(request)) {
            return Optional.empty();
        }
        requireHost(actorId);
        if (status != RoomStatus.WAITING || gameRuntime != null || !participantsReadyToStart()) {
            throw new RoomRuleViolation("GAME_START_CONDITION_NOT_MET");
        }
        return Optional.of(new GameStartToken(
                hostId,
                settings,
                activeGamePlayers(),
                List.copyOf(recentContentIds)
        ));
    }

    public List<RoomEvent> startGame(
            ActorId actorId,
            String requestId,
            GameStartToken expected,
            GameRuntime runtime
    ) {
        var request = processedRequest(actorId, CommandType.GAME_START, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        var current = prepareGameStart(actorId, requestId).orElseThrow();
        if (!current.equals(Objects.requireNonNull(expected, "expected"))) {
            throw new RoomRuleViolation("GAME_START_STATE_CHANGED");
        }
        Objects.requireNonNull(runtime, "runtime");
        if (runtime.gameType() != settings.gameType()) {
            throw new RoomRuleViolation("GAME_TYPE_MISMATCH");
        }
        var activeIds = participants.values().stream()
            .filter(participant -> !participant.spectator())
            .map(Participant::actorId)
            .collect(java.util.stream.Collectors.toSet());
        if (!runtime.playerIds().equals(activeIds)) {
            throw new RoomRuleViolation("GAME_ROSTER_MISMATCH");
        }
        gameRuntime = runtime;
        status = RoomStatus.PLAYING;
        return complete(request, List.of(new RoomEvent.GameStateChanged(nextSequence())));
    }

    public List<RoomEvent> replaceGame(GameRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (status != RoomStatus.PLAYING || gameRuntime == null
            || !gameRuntime.sessionId().equals(runtime.sessionId())) {
            throw new RoomRuleViolation("GAME_NOT_RUNNING");
        }
        gameRuntime = runtime;
        return List.of(new RoomEvent.GameStateChanged(nextSequence()));
    }

    public List<RoomEvent> finishGame(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.RETURN_TO_WAITING, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireHost(actorId);
        return complete(request, finishGameNow());
    }

    public List<RoomEvent> finishGameOnExpiry() {
        return finishGameNow();
    }

    private List<RoomEvent> finishGameNow() {
        if (status != RoomStatus.PLAYING || gameRuntime == null) {
            throw new RoomRuleViolation("GAME_NOT_RUNNING");
        }
        for (var contentId : gameRuntime.usedContentIdsInOrder()) {
            recentContentIds.remove(contentId);
            recentContentIds.addLast(contentId);
            while (recentContentIds.size() > RECENT_CONTENT_LIMIT) {
                recentContentIds.removeFirst();
            }
        }
        gameRuntime = null;
        status = RoomStatus.WAITING;
        participants.replaceAll((actorId, participant) -> participant.withReady(false));
        var events = new ArrayList<RoomEvent>();
        for (var actorId : previewSpectatorPromotions()) {
            var participant = participants.get(actorId);
            participants.put(actorId, participant.withSpectator(false));
            events.add(new RoomEvent.PlayerSpectatorChanged(nextSequence(), actorId, false));
        }
        events.add(new RoomEvent.GameStateChanged(nextSequence()));
        return List.copyOf(events);
    }

    public List<RoomEvent> promoteSpectators() {
        if (status != RoomStatus.PLAYING || gameRuntime == null) {
            throw new RoomRuleViolation("GAME_NOT_RUNNING");
        }
        var available = settings.maxParticipants() - (int) activeParticipantCount();
        if (available <= 0) {
            return List.of();
        }
        var promotions = participants.values().stream()
            .filter(Participant::spectator)
            .sorted(java.util.Comparator.comparingLong(Participant::joinedOrder))
            .limit(available)
            .toList();
        var events = new ArrayList<RoomEvent>();
        for (var participant : promotions) {
            participants.put(participant.actorId(), participant.withSpectator(false));
            events.add(new RoomEvent.PlayerSpectatorChanged(nextSequence(), participant.actorId(), false));
        }
        return List.copyOf(events);
    }

    public List<ActorId> previewSpectatorPromotions() {
        var available = settings.maxParticipants() - (int) activeParticipantCount();
        if (available <= 0) {
            return List.of();
        }
        return participants.values().stream()
                .filter(Participant::spectator)
                .sorted(java.util.Comparator.comparingLong(Participant::joinedOrder))
                .limit(available)
                .map(Participant::actorId)
                .toList();
    }

    public List<GamePlayer> activeGamePlayersAfterPromoting(List<ActorId> promotions) {
        var promotionIds = Set.copyOf(promotions);
        return participants.values().stream()
                .filter(participant -> !participant.spectator() || promotionIds.contains(participant.actorId()))
                .map(participant -> new GamePlayer(participant.actorId(), participant.nickname()))
                .toList();
    }

    public List<RoomEvent> replaceGameAndPromote(GameRuntime runtime, List<ActorId> promotions) {
        Objects.requireNonNull(runtime, "runtime");
        if (status != RoomStatus.PLAYING || gameRuntime == null
                || !gameRuntime.sessionId().equals(runtime.sessionId())) {
            throw new RoomRuleViolation("GAME_NOT_RUNNING");
        }
        var expected = previewSpectatorPromotions();
        if (!expected.equals(List.copyOf(promotions))) {
            throw new RoomRuleViolation("GAME_ROSTER_CHANGED");
        }
        var events = new ArrayList<RoomEvent>();
        for (var actorId : promotions) {
            var participant = requireParticipant(actorId);
            participants.put(actorId, participant.withSpectator(false));
            events.add(new RoomEvent.PlayerSpectatorChanged(nextSequence(), actorId, false));
        }
        gameRuntime = runtime;
        events.add(new RoomEvent.GameStateChanged(nextSequence()));
        return List.copyOf(events);
    }

    public List<GamePlayer> activeGamePlayers() {
        return participants.values().stream()
            .filter(participant -> !participant.spectator())
            .map(participant -> new GamePlayer(participant.actorId(), participant.nickname()))
            .toList();
    }

    public Optional<GameRuntime> gameRuntime() {
        return Optional.ofNullable(gameRuntime);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            id,
            code,
            title,
            visibility,
            settings,
            status,
            hostId,
            sequence,
            List.copyOf(participants.values()),
            participantsReadyToStart(),
            Optional.ofNullable(gameRuntime).map(GameRuntime::snapshot),
            List.copyOf(recentContentIds),
            passwordProtected
        );
    }

    private ProcessedRequest processedRequest(ActorId actorId, CommandType commandType, String requestId) {
        if (requestId == null || requestId.length() != 36) {
            throw new RoomRuleViolation("ROOM_REQUEST_ID_INVALID");
        }
        try {
            var value = UUID.fromString(requestId);
            if (!value.toString().equalsIgnoreCase(requestId)) {
                throw new RoomRuleViolation("ROOM_REQUEST_ID_INVALID");
            }
            return new ProcessedRequest(actorId, commandType, value);
        } catch (IllegalArgumentException exception) {
            throw new RoomRuleViolation("ROOM_REQUEST_ID_INVALID");
        }
    }

    private List<RoomEvent> complete(ProcessedRequest request, List<RoomEvent> events) {
        processedRequests.add(request);
        processedRequestOrder.addLast(request);
        if (processedRequestOrder.size() > REMEMBERED_REQUEST_LIMIT) {
            processedRequests.remove(processedRequestOrder.removeFirst());
        }
        return List.copyOf(events);
    }

    private void requireHost(ActorId actorId) {
        if (!hostId.equals(actorId)) {
            throw new RoomRuleViolation("ROOM_HOST_REQUIRED");
        }
    }

    private Participant requireParticipant(ActorId actorId) {
        var participant = participants.get(actorId);
        if (participant == null) {
            throw new RoomRuleViolation("ROOM_PARTICIPANT_NOT_FOUND");
        }
        return participant;
    }

    private void requireOpen() {
        if (status == RoomStatus.CLOSED) {
            throw new RoomRuleViolation("ROOM_CLOSED");
        }
    }

    private long activeParticipantCount() {
        return participants.values().stream().filter(participant -> !participant.spectator()).count();
    }

    private boolean participantsReadyToStart() {
        return status == RoomStatus.WAITING
            && activeParticipantCount() >= settings.gameType().minimumParticipants()
            && participants.values().stream()
                .filter(participant -> !participant.spectator())
                .filter(participant -> !participant.actorId().equals(hostId))
                .allMatch(Participant::ready);
    }

    private void resetNonHostReadiness() {
        participants.replaceAll((actorId, participant) -> actorId.equals(hostId)
            ? participant
            : participant.withReady(false));
    }

    private void clearReadiness(ActorId actorId) {
        var participant = requireParticipant(actorId);
        participants.put(actorId, participant.withReady(false));
    }

    private long nextSequence() {
        return ++sequence;
    }

    public record Snapshot(
        RoomId id,
        RoomCode code,
        String title,
        Visibility visibility,
        RoomSettings settings,
        RoomStatus status,
        ActorId hostId,
        long sequence,
        List<Participant> participants,
        boolean participantsReadyToStart,
        Optional<GameRuntime.Snapshot> gameRuntime,
        List<UUID> recentContentIds,
        boolean passwordProtected
    ) {
        public Snapshot {
            participants = List.copyOf(participants);
            gameRuntime = Objects.requireNonNull(gameRuntime, "gameRuntime");
            recentContentIds = List.copyOf(recentContentIds);
        }
    }

    public record GameStartToken(
            ActorId hostId,
            RoomSettings settings,
            List<GamePlayer> activePlayers,
            List<UUID> recentContentIds
    ) {
        public GameStartToken {
            Objects.requireNonNull(hostId, "hostId");
            Objects.requireNonNull(settings, "settings");
            activePlayers = List.copyOf(activePlayers);
            recentContentIds = List.copyOf(recentContentIds);
        }
    }

    private record ProcessedRequest(ActorId actorId, CommandType commandType, UUID requestId) {
    }

    private enum CommandType {
        JOIN,
        CHANGE_READY,
        UPDATE_SETTINGS,
        TRANSFER_HOST,
        LEAVE,
        CHAT_SEND,
        GAME_START,
        RETURN_TO_WAITING
    }
}
