package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Participant;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;
import com.minigame.platform.room.domain.RoomSettings;
import com.minigame.platform.room.domain.RoomStatus;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomApplicationService {
    private static final int DEFAULT_ROUNDS = 3;
    private static final int DEFAULT_ACTION_SECONDS = 30;
    private static final int DEFAULT_DISCUSSION_SECONDS = 90;
    private static final String DEFAULT_CATEGORY_PACK = "all";
    private static final int REMEMBERED_CREATE_REQUEST_LIMIT = 1_024;

    private final ActiveRoomRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final RoomEventPublisher eventPublisher;
    private final Clock clock;
    private final ChatPolicy chatPolicy;
    private final Map<RoomId, String> passwordHashes = new ConcurrentHashMap<>();
    private final Map<RoomId, Object> lobbyPublicationLocks = new ConcurrentHashMap<>();
    private final Map<RoomId, Long> lobbyPublishedSequences = new ConcurrentHashMap<>();
    private final Map<CreateRequestKey, CompletableFuture<RoomSnapshotView>> createRequests =
            new ConcurrentHashMap<>();
    private final ArrayDeque<CreateRequestKey> createRequestOrder = new ArrayDeque<>();

    @Autowired
    public RoomApplicationService(
            ActiveRoomRepository repository,
            RoomEventPublisher eventPublisher,
            Clock clock,
            ChatPolicy chatPolicy
    ) {
        this(
                repository,
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                eventPublisher,
                clock,
                chatPolicy
        );
    }

    RoomApplicationService(ActiveRoomRepository repository, PasswordEncoder passwordEncoder) {
        this(
                repository,
                passwordEncoder,
                RoomEventPublisher.noOp(),
                Clock.systemUTC(),
                new ChatPolicy(Clock.systemUTC())
        );
    }

    public RoomApplicationService(
            ActiveRoomRepository repository,
            PasswordEncoder passwordEncoder,
            RoomEventPublisher eventPublisher,
            Clock clock
    ) {
        this(repository, passwordEncoder, eventPublisher, clock, new ChatPolicy(clock));
    }

    public RoomApplicationService(
            ActiveRoomRepository repository,
            PasswordEncoder passwordEncoder,
            RoomEventPublisher eventPublisher,
            Clock clock,
            ChatPolicy chatPolicy
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.chatPolicy = Objects.requireNonNull(chatPolicy, "chatPolicy");
    }

    public RoomSnapshotView create(
            ActorPrincipal actor,
            String title,
            Visibility visibility,
            String password,
            GameType gameType
    ) {
        return create(actor, title, visibility, password, gameType, UUID.randomUUID().toString());
    }

    public RoomSnapshotView create(
            ActorPrincipal actor,
            String title,
            Visibility visibility,
            String password,
            GameType gameType,
            String requestId
    ) {
        Objects.requireNonNull(actor, "actor");
        var requestKey = new CreateRequestKey(actor.actorId().value(), canonicalUuid(requestId));
        var pending = new CompletableFuture<RoomSnapshotView>();
        var existing = createRequests.putIfAbsent(requestKey, pending);
        if (existing != null) {
            return completedCreate(existing);
        }
        try {
            var created = createOnce(actor, title, visibility, password, gameType, requestId);
            pending.complete(created);
            rememberCreateRequest(requestKey);
            return created;
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            createRequests.remove(requestKey, pending);
            throw exception;
        }
    }

    private RoomSnapshotView createOnce(
            ActorPrincipal actor,
            String title,
            Visibility visibility,
            String password,
            GameType gameType,
            String requestId
    ) {
        var settings = new RoomSettings(
                gameType,
                gameType.maximumParticipants(),
                DEFAULT_ROUNDS,
                DEFAULT_ACTION_SECONDS,
                DEFAULT_DISCUSSION_SECONDS,
                DEFAULT_CATEGORY_PACK
        );
        var room = Room.create(
                RoomId.random(),
                repository.generateCode(),
                title,
                visibility,
                settings,
                actor.actorId(),
                actor.nickname()
        );
        var initialSnapshot = room.snapshot();
        var normalizedPassword = normalizePassword(password);
        if (normalizedPassword != null) {
            var encodedPassword = passwordEncoder.encode(normalizedPassword);
            passwordHashes.put(initialSnapshot.id(), encodedPassword);
        }
        try {
            repository.save(room);
        } catch (RuntimeException exception) {
            repository.remove(initialSnapshot.id());
            passwordHashes.remove(initialSnapshot.id());
            throw exception;
        }
        var publishedSnapshot = repository.findById(initialSnapshot.id())
                .orElseThrow(() -> violation("ROOM_NOT_FOUND"));
        var view = snapshotView(publishedSnapshot);
        if (publishedSnapshot.sequence() == 0L) {
            publishLobbyUpsert(publishedSnapshot, actor, requestId);
        }
        return view;
    }

    private static UUID canonicalUuid(String requestId) {
        if (requestId == null || requestId.length() != 36) {
            throw violation("ROOM_REQUEST_ID_INVALID");
        }
        try {
            var value = UUID.fromString(requestId);
            if (!value.toString().equalsIgnoreCase(requestId)) {
                throw violation("ROOM_REQUEST_ID_INVALID");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw violation("ROOM_REQUEST_ID_INVALID");
        }
    }

    private static RoomSnapshotView completedCreate(CompletableFuture<RoomSnapshotView> existing) {
        try {
            return existing.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private synchronized void rememberCreateRequest(CreateRequestKey requestKey) {
        createRequestOrder.addLast(requestKey);
        if (createRequestOrder.size() > REMEMBERED_CREATE_REQUEST_LIMIT) {
            createRequests.remove(createRequestOrder.removeFirst());
        }
    }

    public RoomSnapshotView join(
            ActorPrincipal actor,
            RoomCode code,
            String password,
            String requestId
    ) {
        Objects.requireNonNull(actor, "actor");
        var discovered = repository.findByCode(code).orElseThrow(() -> violation("ROOM_NOT_FOUND"));
        verifyPassword(discovered.id(), password);
        var result = repository.withRoom(discovered.id(), room -> {
            var events = room.join(actor.actorId(), actor.nickname(), false, requestId);
            publishRoomEvents(discovered.id(), actor, requestId, events);
            if (!events.isEmpty()) {
                publishLobbyUpsert(room.snapshot(), actor, requestId);
            }
            return events;
        });
        return snapshotView(result.snapshot());
    }

    public RoomSnapshotView snapshot(ActorPrincipal actor, RoomId roomId) {
        Objects.requireNonNull(actor, "actor");
        var snapshot = find(roomId);
        var participant = snapshot.participants().stream()
                .anyMatch(candidate -> candidate.actorId().equals(actor.actorId()));
        if (!participant) {
            throw violation("ROOM_PARTICIPANT_NOT_FOUND");
        }
        return snapshotView(snapshot);
    }

    public void leave(ActorPrincipal actor, RoomId roomId, String requestId) {
        Objects.requireNonNull(actor, "actor");
        var result = repository.withRoom(roomId, room -> {
            var events = room.leave(actor.actorId(), requestId);
            publishRoomEvents(roomId, actor, requestId, events);
            if (!events.isEmpty()) {
                var snapshot = room.snapshot();
                if (snapshot.status() == RoomStatus.CLOSED) {
                    publishLobbyRemove(snapshot, actor, requestId);
                } else {
                    publishLobbyUpsert(snapshot, actor, requestId);
                }
            }
            return events;
        });
        if (result.events().isEmpty()) {
            return;
        }
        if (result.snapshot().status() == RoomStatus.CLOSED) {
            repository.remove(roomId);
            passwordHashes.remove(roomId);
            chatPolicy.clear(roomId);
        }
    }

    public RoomSnapshotView changeReady(
            ActorPrincipal actor,
            RoomId roomId,
            boolean ready,
            String requestId
    ) {
        Objects.requireNonNull(actor, "actor");
        var result = repository.withRoom(roomId, room -> {
            var events = room.changeReady(actor.actorId(), ready, requestId);
            publishRoomEvents(roomId, actor, requestId, events);
            return events;
        });
        return snapshotView(result.snapshot());
    }

    public RoomSnapshotView updateSettings(
            ActorPrincipal actor,
            RoomId roomId,
            RoomSettings settings,
            String requestId
    ) {
        Objects.requireNonNull(actor, "actor");
        var result = repository.withRoom(roomId, room -> {
            var events = room.updateSettings(actor.actorId(), settings, requestId);
            publishRoomEvents(roomId, actor, requestId, events);
            if (!events.isEmpty()) {
                publishLobbyUpsert(room.snapshot(), actor, requestId);
            }
            return events;
        });
        return snapshotView(result.snapshot());
    }

    public long publishChat(
            ActorPrincipal actor,
            RoomId roomId,
            String requestId,
            ChatPolicy.ChatMessage message
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(message, "message");
        var result = repository.withRoom(roomId, room -> {
            var events = room.acceptChat(actor.actorId(), requestId);
            if (!events.isEmpty()) {
                eventPublisher.publishPublic(EventEnvelope.create(
                        requestId,
                        roomId,
                        actor,
                        "CHAT_MESSAGE",
                        events.getFirst().sequence(),
                        clock,
                        message
                ));
            }
            return events;
        });
        if (result.events().isEmpty()) {
            return -1L;
        }
        return result.events().getFirst().sequence();
    }

    public List<LobbyRoomView> lobbyRooms(String query, GameType gameType, Boolean available) {
        var normalizedQuery = query == null ? null : query.strip().toLowerCase(Locale.ROOT);
        return repository.findAll().stream()
                .filter(room -> room.visibility() == Visibility.PUBLIC)
                .filter(room -> room.status() != RoomStatus.CLOSED)
                .filter(room -> normalizedQuery == null || normalizedQuery.isEmpty()
                        || room.title().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .filter(room -> gameType == null || room.settings().gameType() == gameType)
                .filter(room -> !Boolean.TRUE.equals(available)
                        || activeParticipantCount(room) < room.settings().maxParticipants())
                .sorted(Comparator
                        .comparing((Room.Snapshot room) -> room.status() != RoomStatus.WAITING)
                        .thenComparing(Room.Snapshot::title)
                        .thenComparing(room -> room.id().value()))
                .map(this::lobbyView)
                .toList();
    }

    private Room.Snapshot find(RoomId roomId) {
        return repository.findById(roomId).orElseThrow(() -> violation("ROOM_NOT_FOUND"));
    }

    private void verifyPassword(RoomId roomId, String rawPassword) {
        var encodedPassword = passwordHashes.get(roomId);
        if (encodedPassword == null) {
            return;
        }
        var normalized = normalizePassword(rawPassword);
        if (normalized == null || !passwordEncoder.matches(normalized, encodedPassword)) {
            throw violation("ROOM_PASSWORD_INVALID");
        }
    }

    private RoomSnapshotView snapshotView(Room.Snapshot room) {
        var participants = room.participants().stream().map(this::participantView).toList();
        return new RoomSnapshotView(
                room.id().value().toString(),
                room.code().value(),
                room.title(),
                room.visibility(),
                room.settings().gameType(),
                room.status(),
                passwordHashes.containsKey(room.id()),
                activeParticipantCount(room),
                room.settings().maxParticipants(),
                room.hostId().value(),
                room.sequence(),
                room.settings().rounds(),
                room.settings().actionSeconds(),
                room.settings().discussionSeconds(),
                room.settings().categoryPack(),
                participants
        );
    }

    private LobbyRoomView lobbyView(Room.Snapshot room) {
        var hostNickname = room.participants().stream()
                .filter(participant -> participant.actorId().equals(room.hostId()))
                .map(Participant::nickname)
                .findFirst()
                .orElse("");
        return new LobbyRoomView(
                room.id().value().toString(),
                room.code().value(),
                room.title(),
                room.settings().gameType(),
                room.status(),
                passwordHashes.containsKey(room.id()),
                activeParticipantCount(room),
                room.settings().maxParticipants(),
                hostNickname,
                room.sequence()
        );
    }

    private ParticipantView participantView(Participant participant) {
        return new ParticipantView(
                participant.actorId().value(),
                participant.nickname(),
                participant.ready(),
                participant.spectator()
        );
    }

    private int activeParticipantCount(Room.Snapshot room) {
        return (int) room.participants().stream().filter(participant -> !participant.spectator()).count();
    }

    private void publishRoomEvents(
            RoomId roomId,
            ActorPrincipal actor,
            String requestId,
            List<RoomEvent> events
    ) {
        for (var event : events) {
            if (event instanceof RoomEvent.ChatAccepted) {
                continue;
            }
            eventPublisher.publishPublic(EventEnvelope.create(
                    requestId,
                    roomId,
                    actor,
                    eventType(event),
                    event.sequence(),
                    clock,
                    eventPayload(event)
            ));
        }
    }

    private void publishLobbyUpsert(
            Room.Snapshot room,
            ActorPrincipal actor,
            String requestId
    ) {
        if (room.visibility() != Visibility.PUBLIC || room.status() == RoomStatus.CLOSED) {
            return;
        }
        publishLobbyInSequence(room.id(), room.sequence(), () ->
                eventPublisher.publishLobby(EventEnvelope.create(
                        requestId,
                        room.id(),
                        actor,
                        "LOBBY_ROOM_UPSERT",
                        room.sequence(),
                        clock,
                        lobbyView(room)
                ))
        );
    }

    private void publishLobbyRemove(
            Room.Snapshot room,
            ActorPrincipal actor,
            String requestId
    ) {
        if (room.visibility() != Visibility.PUBLIC) {
            return;
        }
        publishLobbyInSequence(room.id(), room.sequence(), () ->
                eventPublisher.publishLobby(EventEnvelope.create(
                        requestId,
                        room.id(),
                        actor,
                        "LOBBY_ROOM_REMOVE",
                        room.sequence(),
                        clock,
                        Map.of("roomId", room.id().value().toString())
                ))
        );
    }

    private void publishLobbyInSequence(RoomId roomId, long sequence, Runnable publication) {
        var lock = lobbyPublicationLocks.computeIfAbsent(roomId, ignored -> new Object());
        synchronized (lock) {
            var lastPublished = lobbyPublishedSequences.get(roomId);
            if (lastPublished != null && sequence <= lastPublished) {
                return;
            }
            publication.run();
            lobbyPublishedSequences.put(roomId, sequence);
        }
    }

    private static String eventType(RoomEvent event) {
        return switch (event) {
            case RoomEvent.ParticipantJoined ignored -> "PLAYER_JOINED";
            case RoomEvent.ReadyChanged ignored -> "PLAYER_READY_CHANGED";
            case RoomEvent.SettingsUpdated ignored -> "ROOM_SETTINGS_UPDATED";
            case RoomEvent.HostTransferred ignored -> "HOST_TRANSFERRED";
            case RoomEvent.ParticipantLeft ignored -> "PLAYER_LEFT";
            case RoomEvent.RoomClosed ignored -> "ROOM_CLOSED";
            case RoomEvent.ChatAccepted ignored -> "CHAT_MESSAGE";
        };
    }

    private static Object eventPayload(RoomEvent event) {
        return switch (event) {
            case RoomEvent.ParticipantJoined joined -> Map.of(
                    "actorId", joined.participant().actorId().value(),
                    "nickname", joined.participant().nickname(),
                    "ready", joined.participant().ready(),
                    "spectator", joined.participant().spectator()
            );
            case RoomEvent.ReadyChanged changed -> Map.of(
                    "actorId", changed.actorId().value(),
                    "ready", changed.ready()
            );
            case RoomEvent.SettingsUpdated updated -> Map.of(
                    "gameType", updated.settings().gameType(),
                    "maxParticipants", updated.settings().maxParticipants(),
                    "rounds", updated.settings().rounds(),
                    "actionSeconds", updated.settings().actionSeconds(),
                    "discussionSeconds", updated.settings().discussionSeconds(),
                    "categoryPack", updated.settings().categoryPack()
            );
            case RoomEvent.HostTransferred transferred -> Map.of(
                    "previousHostId", transferred.previousHostId().value(),
                    "newHostId", transferred.newHostId().value()
            );
            case RoomEvent.ParticipantLeft left -> Map.of(
                    "actorId", left.actorId().value()
            );
            case RoomEvent.RoomClosed ignored -> Map.of();
            case RoomEvent.ChatAccepted ignored -> Map.of();
        };
    }

    private static String normalizePassword(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        return password;
    }

    private static RoomRuleViolation violation(String code) {
        return new RoomRuleViolation(code);
    }

    public record ParticipantView(
            String actorId,
            String nickname,
            boolean ready,
            boolean spectator
    ) {
    }

    public record RoomSnapshotView(
            String roomId,
            String code,
            String title,
            Visibility visibility,
            GameType gameType,
            RoomStatus status,
            boolean passwordProtected,
            int participantCount,
            int maxParticipants,
            String hostId,
            long sequence,
            int rounds,
            int actionSeconds,
            int discussionSeconds,
            String categoryPack,
            List<ParticipantView> participants
    ) {
    }

    public record LobbyRoomView(
            String roomId,
            String code,
            String title,
            GameType gameType,
            RoomStatus status,
            boolean passwordProtected,
            int participantCount,
            int maxParticipants,
            String hostNickname,
            long sequence
    ) {
    }

    private record CreateRequestKey(String actorId, UUID requestId) {
    }
}
