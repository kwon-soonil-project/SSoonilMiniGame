package com.minigame.platform.room.adapter.in.realtime;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.application.RoomMutationResult;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomCommandGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T01:00:00Z"), ZoneOffset.UTC);
    private static final ActorPrincipal HOST = ActorPrincipal.guest(new ActorId("realtime-host"), "방장감자");
    private static final String READY_REQUEST = "00000000-0000-0000-0000-000000005101";

    private RecordingPublisher publisher;
    private RoomApplicationService rooms;
    private ChatPolicy chatPolicy;
    private RoomCommandGateway gateway;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        chatPolicy = new ChatPolicy(CLOCK);
        rooms = new RoomApplicationService(
                new InMemoryActiveRoomRepository(),
                new PlainPasswordEncoder(),
                publisher,
                CLOCK,
                chatPolicy
        );
        var created = rooms.create(
                HOST,
                "실시간 테스트방",
                Visibility.PUBLIC,
                null,
                GameType.LIAR,
                "00000000-0000-0000-0000-000000005100"
        );
        roomId = UUID.fromString(created.roomId());
        publisher.clear();
        gateway = new RoomCommandGateway(rooms, chatPolicy, publisher, CLOCK);
    }

    @Test
    void publishesReadyEventWithRoomSequence() {
        gateway.handle(
                roomId,
                HOST,
                new RoomCommands.RoomCommand(READY_REQUEST, "PLAYER_READY", Map.of("ready", true))
        );

        var event = publisher.publicEvents.getLast();
        assertThat(event.type()).isEqualTo("PLAYER_READY_CHANGED");
        assertThat(event.requestId()).isEqualTo(READY_REQUEST);
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.actorId()).isEqualTo("realtime-host");
        assertThat(event.sequence()).isEqualTo(1L);
        assertThat(event.occurredAt()).isEqualTo(CLOCK.instant());
        assertThat(event.payload()).isEqualTo(Map.of("actorId", "realtime-host", "ready", true));
    }

    @Test
    void suppressesDuplicateReadyRequestWithinTheActorAndCommandScope() {
        var command = new RoomCommands.RoomCommand(READY_REQUEST, "PLAYER_READY", Map.of("ready", true));

        gateway.handle(roomId, HOST, command);
        gateway.handle(roomId, HOST, command);

        assertThat(publisher.publicEvents).hasSize(1);
        assertThat(rooms.snapshot(HOST, new RoomId(roomId)).sequence()).isEqualTo(1L);
    }

    @Test
    void changesGameTypeAndAppliesTheSelectedGameBounds() {
        gateway.handle(
                roomId,
                HOST,
                new RoomCommands.RoomCommand(
                        "00000000-0000-0000-0000-000000005118",
                        "ROOM_SETTINGS_UPDATE",
                        Map.of(
                                "gameType", "CHOSUNG",
                                "maxParticipants", 11,
                                "rounds", 5,
                                "actionSeconds", 20,
                                "discussionSeconds", 45,
                                "categoryPack", "korean"
                        )
                )
        );

        var snapshot = rooms.snapshot(HOST, new RoomId(roomId));
        assertThat(snapshot.gameType()).isEqualTo(GameType.CHOSUNG);
        assertThat(snapshot.maxParticipants()).isEqualTo(11);
        assertThat(snapshot.rounds()).isEqualTo(5);
        assertThat(publisher.publicEvents.getLast().type()).isEqualTo("ROOM_SETTINGS_UPDATED");
        assertThat(publisher.privateEvents).isEmpty();
    }

    @Test
    void broadcastsAnAcceptedChatAndKeepsItInBoundedHistory() {
        gateway.handle(
                roomId,
                HOST,
                new RoomCommands.RoomCommand(
                        "00000000-0000-0000-0000-000000005102",
                        "CHAT_SEND",
                        Map.of("body", "  다들 준비됐어?  ")
                )
        );

        var event = publisher.publicEvents.getLast();
        assertThat(event.type()).isEqualTo("CHAT_MESSAGE");
        assertThat(event.sequence()).isEqualTo(1L);
        assertThat(event.payload()).isInstanceOf(ChatPolicy.ChatMessage.class);
        assertThat(((ChatPolicy.ChatMessage) event.payload()).body()).isEqualTo("다들 준비됐어?");
    }

    @Test
    void publishesConcurrentRoomEventsInAggregateSequenceOrder() throws Exception {
        var repository = new AfterChatReservationBarrierRepository();
        var orderedPublisher = new RecordingPublisher();
        var orderedRooms = new RoomApplicationService(
                repository,
                new PlainPasswordEncoder(),
                orderedPublisher,
                CLOCK
        );
        var created = orderedRooms.create(
                HOST,
                "순서 보장방",
                Visibility.PUBLIC,
                null,
                GameType.LIAR,
                "00000000-0000-0000-0000-000000005108"
        );
        var orderedRoomId = UUID.fromString(created.roomId());
        orderedPublisher.clear();
        var orderedGateway = new RoomCommandGateway(
                orderedRooms,
                new ChatPolicy(CLOCK),
                orderedPublisher,
                CLOCK
        );

        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = executor.submit(() -> orderedGateway.handle(
                    orderedRoomId,
                    HOST,
                    new RoomCommands.RoomCommand(
                            "00000000-0000-0000-0000-000000005109",
                            "CHAT_SEND",
                            Map.of("body", "first")
                    )
            ));
            assertThat(repository.chatReserved.await(5, TimeUnit.SECONDS)).isTrue();

            orderedGateway.handle(
                    orderedRoomId,
                    HOST,
                    new RoomCommands.RoomCommand(
                            "00000000-0000-0000-0000-000000005110",
                            "PLAYER_READY",
                            Map.of("ready", true)
                    )
            );
            repository.releaseChat.countDown();
            chat.get(5, TimeUnit.SECONDS);
        }

        assertThat(orderedPublisher.publicEvents).extracting(EventEnvelope::sequence)
                .containsExactly(1L, 2L);
        assertThat(orderedPublisher.publicEvents).extracting(EventEnvelope::type)
                .containsExactly("CHAT_MESSAGE", "PLAYER_READY_CHANGED");
    }

    @Test
    void publishesConcurrentLobbyDeltasInAggregateSequenceOrder() throws Exception {
        var repository = new AfterJoinBarrierRepository();
        var orderedPublisher = new RecordingPublisher();
        var orderedRooms = new RoomApplicationService(
                repository,
                new PlainPasswordEncoder(),
                orderedPublisher,
                CLOCK
        );
        var created = orderedRooms.create(
                HOST,
                "로비 순서 보장방",
                Visibility.PUBLIC,
                null,
                GameType.LIAR,
                "00000000-0000-0000-0000-000000005111"
        );
        var orderedRoomId = new RoomId(UUID.fromString(created.roomId()));
        var guest = ActorPrincipal.guest(new ActorId("ordered-guest"), "순서감자");
        orderedPublisher.clear();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var join = executor.submit(() -> orderedRooms.join(
                    guest,
                    new RoomCode(created.code()),
                    null,
                    "00000000-0000-0000-0000-000000005112"
            ));
            assertThat(repository.joinReserved.await(5, TimeUnit.SECONDS)).isTrue();

            orderedRooms.updateSettings(
                    HOST,
                    orderedRoomId,
                    new com.minigame.platform.room.domain.RoomSettings(
                            GameType.LIAR, 9, 4, 30, 90, "all"
                    ),
                    "00000000-0000-0000-0000-000000005113"
            );
            repository.releaseJoin.countDown();
            join.get(5, TimeUnit.SECONDS);
        }

        assertThat(orderedPublisher.lobbyEvents).extracting(EventEnvelope::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void sendsPolicyFailuresOnlyToTheActorPrivateQueue() {
        gateway.handle(
                roomId,
                HOST,
                new RoomCommands.RoomCommand(
                        "00000000-0000-0000-0000-000000005103",
                        "CHAT_SEND",
                        Map.of("body", "https://example.com")
                )
        );

        assertThat(publisher.publicEvents).isEmpty();
        assertThat(publisher.privateEvents).singleElement().satisfies(delivery -> {
            assertThat(delivery.userName()).isEqualTo("realtime-host");
            assertThat(delivery.event().type()).isEqualTo("COMMAND_REJECTED");
            assertThat(delivery.event().payload()).isEqualTo(Map.of("code", "CHAT_URL_NOT_ALLOWED"));
        });
        assertThat(rooms.snapshot(HOST, new RoomId(roomId)).sequence()).isZero();
    }

    @Test
    void clearsBoundedChatHistoryWhenTheRoomCloses() {
        gateway.handle(
                roomId,
                HOST,
                new RoomCommands.RoomCommand(
                        "00000000-0000-0000-0000-000000005114",
                        "CHAT_SEND",
                        Map.of("body", "temporary")
                )
        );
        assertThat(chatPolicy.history(new RoomId(roomId))).hasSize(1);

        rooms.leave(
                HOST,
                new RoomId(roomId),
                "00000000-0000-0000-0000-000000005115"
        );

        assertThat(chatPolicy.history(new RoomId(roomId))).isEmpty();
    }

    @Test
    void rejectsAnyMessageBoundaryPrincipalThatIsNotAnActorPrincipal() {
        Principal providerPrincipal = () -> "oauth-provider-user";

        assertThatThrownBy(() -> gateway.handle(
                roomId,
                providerPrincipal,
                new RoomCommands.RoomCommand(READY_REQUEST, "PLAYER_READY", Map.of("ready", true))
        )).isInstanceOf(AccessDeniedException.class);

        assertThat(publisher.publicEvents).isEmpty();
        assertThat(publisher.privateEvents).isEmpty();
    }

    @Test
    void publishesLobbyDeltasForCreateJoinAndFinalLeave() {
        var guest = ActorPrincipal.guest(new ActorId("realtime-guest"), "참가감자");
        var code = new RoomCode(rooms.snapshot(HOST, new RoomId(roomId)).code());

        rooms.create(
                HOST,
                "로비 생성 알림방",
                Visibility.PUBLIC,
                null,
                GameType.CHOSUNG,
                "00000000-0000-0000-0000-000000005107"
        );

        rooms.join(
                guest,
                code,
                null,
                "00000000-0000-0000-0000-000000005104"
        );
        rooms.leave(
                guest,
                new RoomId(roomId),
                "00000000-0000-0000-0000-000000005105"
        );
        rooms.leave(
                HOST,
                new RoomId(roomId),
                "00000000-0000-0000-0000-000000005106"
        );

        assertThat(publisher.lobbyEvents).extracting(EventEnvelope::type)
                .containsExactly(
                        "LOBBY_ROOM_UPSERT",
                        "LOBBY_ROOM_UPSERT",
                        "LOBBY_ROOM_UPSERT",
                        "LOBBY_ROOM_REMOVE"
                );
        assertThat(publisher.lobbyEvents.getFirst().sequence()).isZero();
        assertThat(publisher.lobbyEvents.getLast().sequence()).isEqualTo(4L);
    }

    @Test
    void doesNotPublishAStaleCreationDeltaWhenJoinWinsThePublicationRace() throws Exception {
        var repository = new AfterSaveBarrierRepository();
        var orderedPublisher = new RecordingPublisher();
        var orderedRooms = new RoomApplicationService(
                repository,
                new PlainPasswordEncoder(),
                orderedPublisher,
                CLOCK
        );
        var guest = ActorPrincipal.guest(new ActorId("creation-race-guest"), "생성경쟁감자");

        try (var executor = Executors.newSingleThreadExecutor()) {
            var creation = executor.submit(() -> orderedRooms.create(
                    HOST,
                    "생성 경쟁방",
                    Visibility.PUBLIC,
                    null,
                    GameType.LIAR,
                    "00000000-0000-0000-0000-000000005116"
            ));
            assertThat(repository.saved.await(5, TimeUnit.SECONDS)).isTrue();
            var saved = repository.findAll().getFirst();

            orderedRooms.join(
                    guest,
                    saved.code(),
                    null,
                    "00000000-0000-0000-0000-000000005117"
            );
            repository.releaseSave.countDown();
            creation.get(5, TimeUnit.SECONDS);
        }

        assertThat(orderedPublisher.lobbyEvents).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("LOBBY_ROOM_UPSERT");
            assertThat(event.sequence()).isEqualTo(1L);
        });
    }

    @Test
    void serializesCreationAndImmediateJoinLobbyPublication() throws Exception {
        var repository = new InMemoryActiveRoomRepository();
        var barrierPublisher = new CreationPublishBarrierPublisher();
        var orderedRooms = new RoomApplicationService(
                repository,
                new PlainPasswordEncoder(),
                barrierPublisher,
                CLOCK
        );
        var guest = ActorPrincipal.guest(new ActorId("immediate-join-guest"), "즉시참가감자");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> orderedRooms.create(
                    HOST,
                    "생성 발행 순서방",
                    Visibility.PUBLIC,
                    null,
                    GameType.LIAR,
                    "00000000-0000-0000-0000-000000005119"
            ));
            assertThat(barrierPublisher.creationAtPublisher.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = (RoomApplicationService.LobbyRoomView) barrierPublisher.pendingCreation.payload();

            var join = executor.submit(() -> orderedRooms.join(
                    guest,
                    new RoomCode(pending.code()),
                    null,
                    "00000000-0000-0000-0000-000000005120"
            ));
            barrierPublisher.joinAtPublisher.await(300, TimeUnit.MILLISECONDS);
            barrierPublisher.releaseCreation.countDown();
            creation.get(5, TimeUnit.SECONDS);
            join.get(5, TimeUnit.SECONDS);
        }

        assertThat(barrierPublisher.lobbyEvents).extracting(EventEnvelope::sequence)
                .containsExactly(0L, 1L);
    }

    @Test
    void returnsOneRoomAndOneLobbyDeltaForConcurrentDuplicateCreateRequests() throws Exception {
        var repository = new AfterSaveBarrierRepository();
        var duplicatePublisher = new RecordingPublisher();
        var duplicateRooms = new RoomApplicationService(
                repository,
                new PlainPasswordEncoder(),
                duplicatePublisher,
                CLOCK
        );
        var requestId = "00000000-0000-0000-0000-000000005121";

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> duplicateRooms.create(
                    HOST, "중복 생성방", Visibility.PUBLIC, null, GameType.LIAR, requestId
            ));
            assertThat(repository.saved.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> duplicateRooms.create(
                    HOST, "중복 생성방", Visibility.PUBLIC, null, GameType.LIAR, requestId
            ));

            repository.releaseSave.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
        }

        assertThat(repository.findAll()).hasSize(1);
        assertThat(duplicatePublisher.lobbyEvents).hasSize(1);
    }

    private static final class RecordingPublisher implements RoomEventPublisher {
        private final List<EventEnvelope<?>> publicEvents = new CopyOnWriteArrayList<>();
        private final List<PrivateDelivery> privateEvents = new CopyOnWriteArrayList<>();
        private final List<EventEnvelope<?>> lobbyEvents = new CopyOnWriteArrayList<>();

        @Override
        public void publishPublic(EventEnvelope<?> event) {
            publicEvents.add(event);
        }

        @Override
        public void publishPrivate(String userName, EventEnvelope<?> event) {
            privateEvents.add(new PrivateDelivery(userName, event));
        }

        @Override
        public void publishLobby(EventEnvelope<?> event) {
            lobbyEvents.add(event);
        }

        void clear() {
            publicEvents.clear();
            privateEvents.clear();
            lobbyEvents.clear();
        }
    }

    private static final class CreationPublishBarrierPublisher implements RoomEventPublisher {
        private final List<EventEnvelope<?>> lobbyEvents = new CopyOnWriteArrayList<>();
        private final CountDownLatch creationAtPublisher = new CountDownLatch(1);
        private final CountDownLatch joinAtPublisher = new CountDownLatch(1);
        private final CountDownLatch releaseCreation = new CountDownLatch(1);
        private volatile EventEnvelope<?> pendingCreation;

        @Override
        public void publishPublic(EventEnvelope<?> event) {
        }

        @Override
        public void publishPrivate(String userName, EventEnvelope<?> event) {
        }

        @Override
        public void publishLobby(EventEnvelope<?> event) {
            if (event.sequence() == 0L && "LOBBY_ROOM_UPSERT".equals(event.type())) {
                pendingCreation = event;
                creationAtPublisher.countDown();
                try {
                    if (!releaseCreation.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("creation publication release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            } else if (event.sequence() == 1L && "LOBBY_ROOM_UPSERT".equals(event.type())) {
                joinAtPublisher.countDown();
            }
            lobbyEvents.add(event);
        }
    }

    private record PrivateDelivery(String userName, EventEnvelope<?> event) {
    }

    private static final class AfterChatReservationBarrierRepository implements ActiveRoomRepository {
        private final InMemoryActiveRoomRepository delegate = new InMemoryActiveRoomRepository();
        private final CountDownLatch chatReserved = new CountDownLatch(1);
        private final CountDownLatch releaseChat = new CountDownLatch(1);

        @Override
        public void save(Room room) {
            delegate.save(room);
        }

        @Override
        public Optional<Room.Snapshot> findById(RoomId roomId) {
            return delegate.findById(roomId);
        }

        @Override
        public Optional<Room.Snapshot> findByCode(RoomCode code) {
            return delegate.findByCode(code);
        }

        @Override
        public List<Room.Snapshot> findAll() {
            return delegate.findAll();
        }

        @Override
        public RoomCode generateCode() {
            return delegate.generateCode();
        }

        @Override
        public RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command) {
            var result = delegate.withRoom(roomId, command);
            if (!result.events().isEmpty() && result.events().getFirst() instanceof RoomEvent.ChatAccepted) {
                chatReserved.countDown();
                try {
                    if (!releaseChat.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("chat release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        @Override
        public void remove(RoomId roomId) {
            delegate.remove(roomId);
        }
    }

    private static final class AfterJoinBarrierRepository implements ActiveRoomRepository {
        private final InMemoryActiveRoomRepository delegate = new InMemoryActiveRoomRepository();
        private final CountDownLatch joinReserved = new CountDownLatch(1);
        private final CountDownLatch releaseJoin = new CountDownLatch(1);

        @Override
        public void save(Room room) {
            delegate.save(room);
        }

        @Override
        public Optional<Room.Snapshot> findById(RoomId roomId) {
            return delegate.findById(roomId);
        }

        @Override
        public Optional<Room.Snapshot> findByCode(RoomCode code) {
            return delegate.findByCode(code);
        }

        @Override
        public List<Room.Snapshot> findAll() {
            return delegate.findAll();
        }

        @Override
        public RoomCode generateCode() {
            return delegate.generateCode();
        }

        @Override
        public RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command) {
            var result = delegate.withRoom(roomId, command);
            if (!result.events().isEmpty() && result.events().getFirst() instanceof RoomEvent.ParticipantJoined) {
                joinReserved.countDown();
                try {
                    if (!releaseJoin.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("join release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        @Override
        public void remove(RoomId roomId) {
            delegate.remove(roomId);
        }
    }

    private static final class AfterSaveBarrierRepository implements ActiveRoomRepository {
        private final InMemoryActiveRoomRepository delegate = new InMemoryActiveRoomRepository();
        private final CountDownLatch saved = new CountDownLatch(1);
        private final CountDownLatch releaseSave = new CountDownLatch(1);

        @Override
        public void save(Room room) {
            delegate.save(room);
            saved.countDown();
            try {
                if (!releaseSave.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("save release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        @Override
        public Optional<Room.Snapshot> findById(RoomId roomId) {
            return delegate.findById(roomId);
        }

        @Override
        public Optional<Room.Snapshot> findByCode(RoomCode code) {
            return delegate.findByCode(code);
        }

        @Override
        public List<Room.Snapshot> findAll() {
            return delegate.findAll();
        }

        @Override
        public RoomCode generateCode() {
            return delegate.generateCode();
        }

        @Override
        public RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command) {
            return delegate.withRoom(roomId, command);
        }

        @Override
        public void remove(RoomId roomId) {
            delegate.remove(roomId);
        }
    }

    private static final class PlainPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return rawPassword.toString();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword.contentEquals(rawPassword);
        }
    }
}
