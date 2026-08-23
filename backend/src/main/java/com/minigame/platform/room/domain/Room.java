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

public final class Room {
    private static final int REMEMBERED_REQUEST_LIMIT = 1_024;

    private final RoomId id;
    private final RoomCode code;
    private final String title;
    private final Visibility visibility;
    private final Map<ActorId, Participant> participants = new LinkedHashMap<>();
    private final Set<String> processedRequestIds = new HashSet<>();
    private final ArrayDeque<String> processedRequestOrder = new ArrayDeque<>();
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
        if (isDuplicate(requestId)) {
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
        return List.of(new RoomEvent.ParticipantJoined(nextSequence(), participant));
    }

    public List<RoomEvent> changeReady(ActorId actorId, boolean ready, String requestId) {
        if (isDuplicate(requestId)) {
            return List.of();
        }
        if (status != RoomStatus.WAITING) {
            throw new RoomRuleViolation("ROOM_READY_LOCKED");
        }
        var participant = requireParticipant(actorId);
        if (participant.spectator()) {
            throw new RoomRuleViolation("ROOM_SPECTATOR_CANNOT_READY");
        }
        participants.put(actorId, participant.withReady(ready));
        return List.of(new RoomEvent.ReadyChanged(nextSequence(), actorId, ready));
    }

    public List<RoomEvent> updateSettings(ActorId actorId, RoomSettings next, String requestId) {
        if (isDuplicate(requestId)) {
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
        participants.replaceAll((id, participant) -> participant.withReady(false));
        return List.of(new RoomEvent.SettingsUpdated(nextSequence(), next));
    }

    public List<RoomEvent> transferHost(ActorId actorId, ActorId nextHostId, String requestId) {
        if (isDuplicate(requestId)) {
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
        return List.of(new RoomEvent.HostTransferred(nextSequence(), previousHostId, nextHostId));
    }

    public List<RoomEvent> leave(ActorId actorId, String requestId) {
        if (isDuplicate(requestId)) {
            return List.of();
        }
        requireOpen();
        requireParticipant(actorId);
        participants.remove(actorId);
        var events = new ArrayList<RoomEvent>();
        events.add(new RoomEvent.ParticipantLeft(nextSequence(), actorId));
        if (participants.isEmpty()) {
            status = RoomStatus.CLOSED;
            events.add(new RoomEvent.RoomClosed(nextSequence()));
            return List.copyOf(events);
        }
        if (hostId.equals(actorId)) {
            var replacement = participants.values().stream()
                .filter(participant -> !participant.spectator())
                .findFirst();
            if (replacement.isEmpty()) {
                status = RoomStatus.CLOSED;
                events.add(new RoomEvent.RoomClosed(nextSequence()));
                return List.copyOf(events);
            }
            hostId = replacement.orElseThrow().actorId();
            events.add(new RoomEvent.HostTransferred(nextSequence(), actorId, hostId));
        }
        return List.copyOf(events);
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
            List.copyOf(participants.values())
        );
    }

    private boolean isDuplicate(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new RoomRuleViolation("ROOM_REQUEST_ID_REQUIRED");
        }
        if (!processedRequestIds.add(requestId)) {
            return true;
        }
        processedRequestOrder.addLast(requestId);
        if (processedRequestOrder.size() > REMEMBERED_REQUEST_LIMIT) {
            processedRequestIds.remove(processedRequestOrder.removeFirst());
        }
        return false;
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
        List<Participant> participants
    ) {
    }
}
