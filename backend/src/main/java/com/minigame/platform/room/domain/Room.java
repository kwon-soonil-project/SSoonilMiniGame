package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Room {
    private static final int REMEMBERED_REQUEST_LIMIT = 1_024;

    private final RoomId id;
    private final RoomCode code;
    private final String title;
    private final Visibility visibility;
    private final Map<ActorId, Participant> participants = new LinkedHashMap<>();
    private final Set<ProcessedRequest> processedRequests = new HashSet<>();
    private final ArrayDeque<ProcessedRequest> processedRequestOrder = new ArrayDeque<>();
    private RoomSettings settings;
    private RoomStatus status;
    private ActorId hostId;
    private long sequence;
    private long nextJoinedOrder;

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
        var participant = new Participant(actorId, nickname, false, spectator, nextJoinedOrder++);
        participants.put(actorId, participant);
        if (!spectator) {
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

    public List<RoomEvent> acceptChat(ActorId actorId, String requestId) {
        var request = processedRequest(actorId, CommandType.CHAT_SEND, requestId);
        if (processedRequests.contains(request)) {
            return List.of();
        }
        requireOpen();
        requireParticipant(actorId);
        return complete(request, List.of(new RoomEvent.ChatAccepted(nextSequence())));
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
            participantsReadyToStart()
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
        return activeParticipantCount() >= settings.gameType().minimumParticipants()
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
        boolean participantsReadyToStart
    ) {
    }

    private record ProcessedRequest(ActorId actorId, CommandType commandType, UUID requestId) {
    }

    private enum CommandType {
        JOIN,
        CHANGE_READY,
        UPDATE_SETTINGS,
        TRANSFER_HOST,
        LEAVE,
        CHAT_SEND
    }
}
