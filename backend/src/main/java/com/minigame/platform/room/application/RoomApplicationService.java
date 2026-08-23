package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Participant;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;
import com.minigame.platform.room.domain.RoomSettings;
import com.minigame.platform.room.domain.RoomStatus;
import com.minigame.platform.room.domain.Visibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomApplicationService {
    private static final int DEFAULT_ROUNDS = 3;
    private static final int DEFAULT_ACTION_SECONDS = 30;
    private static final int DEFAULT_DISCUSSION_SECONDS = 90;
    private static final String DEFAULT_CATEGORY_PACK = "all";

    private final ActiveRoomRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Map<RoomId, String> passwordHashes = new ConcurrentHashMap<>();

    @Autowired
    public RoomApplicationService(ActiveRoomRepository repository) {
        this(repository, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    RoomApplicationService(ActiveRoomRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
    }

    public RoomSnapshotView create(
            ActorPrincipal actor,
            String title,
            Visibility visibility,
            String password,
            GameType gameType
    ) {
        Objects.requireNonNull(actor, "actor");
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
        repository.save(room);
        var normalizedPassword = normalizePassword(password);
        if (normalizedPassword != null) {
            passwordHashes.put(room.snapshot().id(), passwordEncoder.encode(normalizedPassword));
        }
        return snapshotView(room.snapshot());
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
        repository.withRoom(
                discovered.id(),
                room -> room.join(actor.actorId(), actor.nickname(), false, requestId)
        );
        return snapshotView(find(discovered.id()));
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
        repository.withRoom(roomId, room -> room.leave(actor.actorId(), requestId));
        var snapshot = find(roomId);
        if (snapshot.status() == RoomStatus.CLOSED) {
            repository.remove(roomId);
            passwordHashes.remove(roomId);
        }
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
                hostNickname
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
            String hostNickname
    ) {
    }
}
