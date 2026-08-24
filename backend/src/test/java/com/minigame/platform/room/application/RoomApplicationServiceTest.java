package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.game.application.GameApplicationService;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomFixture;
import com.minigame.platform.room.domain.RoomRuleViolation;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomApplicationServiceTest {
    private static final ActorPrincipal HOST = ActorPrincipal.guest(new ActorId("atomic-host"), "원자감자");
    private static final ActorPrincipal GUEST = ActorPrincipal.guest(new ActorId("atomic-guest"), "참가감자");
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000008001";

    @Test
    void protectedRoomIsNeverVisibleBeforeItsHashIsRegistered() throws Exception {
        var repository = new SaveBarrierRepository();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var creation = executor.submit(() -> service.create(
                    HOST, "원자적 비밀방", Visibility.PUBLIC, "1234", GameType.LIAR
            ));
            assertThat(repository.saved.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.lobbyRooms(null, null, null))
                    .singleElement()
                    .extracting(RoomApplicationService.LobbyRoomView::passwordProtected)
                    .isEqualTo(true);

            repository.release.countDown();
            assertThat(creation.get(5, TimeUnit.SECONDS).passwordProtected()).isTrue();
        }
    }

    @Test
    void failedSaveRollsBackPreparedPasswordHash() {
        var repository = new FailingSaveRepository();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder());

        assertThatThrownBy(() -> service.create(
                HOST, "저장 실패 방", Visibility.PUBLIC, "1234", GameType.LIAR
        )).isInstanceOf(IllegalStateException.class).hasMessage("save failed");

        assertThat(service.lobbyRooms(null, null, null)).isEmpty();
        repository.publishCapturedRoom();
        assertThat(service.lobbyRooms(null, null, null))
                .singleElement()
                .extracting(RoomApplicationService.LobbyRoomView::passwordProtected)
                .isEqualTo(false);
    }

    @Test
    void joinReturnsTheSnapshotCapturedWithTheMutationLock() {
        var repository = new EvictAfterMutationRepository();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder());
        var created = service.create(HOST, "입장 경쟁 방", Visibility.PUBLIC, null, GameType.LIAR);
        repository.evictAfterNextMutation = true;

        var joined = service.join(GUEST, new RoomCode(created.code()), null, REQUEST_ID);

        assertThat(joined.participantCount()).isEqualTo(2);
        assertThat(repository.findById(new RoomId(java.util.UUID.fromString(created.roomId())))).isEmpty();
    }

    @Test
    void existingParticipantCanRejoinWithoutRepeatingPasswordVerificationOrMutation() {
        var repository = new InMemoryActiveRoomRepository();
        var publisher = new RecordingPublisher();
        var encoder = new CountingPasswordEncoder();
        var service = new RoomApplicationService(
                repository, encoder, publisher, Clock.systemUTC()
        );
        var created = service.create(HOST, "재접속 비밀방", Visibility.PUBLIC, "1234", GameType.LIAR);

        var rejoined = service.join(
                HOST, new RoomCode(created.code()), "wrong",
                "00000000-0000-0000-0000-000000008011"
        );

        assertThat(rejoined.sequence()).isZero();
        assertThat(rejoined.participantCount()).isEqualTo(1);
        assertThat(rejoined.participants()).singleElement()
                .extracting(RoomApplicationService.ParticipantView::actorId)
                .isEqualTo(HOST.actorId().value());
        assertThat(publisher.publicEvents).isEmpty();
        assertThat(publisher.lobbyEvents).hasSize(1);
        assertThat(encoder.matchesCalls).hasValue(0);
    }

    @Test
    void participantSnapshotContainsOnlyTheBoundedChatHistoryContract() {
        var repository = new InMemoryActiveRoomRepository();
        var chatPolicy = new ChatPolicy(Clock.systemUTC());
        var service = new RoomApplicationService(
                repository, new TestPasswordEncoder(), new RecordingPublisher(), Clock.systemUTC(), chatPolicy
        );
        var created = service.create(HOST, "대화 복구 방", Visibility.PRIVATE, "1234", GameType.LIAR);
        var roomId = new RoomId(java.util.UUID.fromString(created.roomId()));
        chatPolicy.accept(
                roomId,
                HOST,
                "00000000-0000-0000-0000-000000008012",
                "복구할 메시지",
                message -> service.publishChat(
                        HOST, roomId, "00000000-0000-0000-0000-000000008012", message
                )
        );

        var snapshot = service.snapshot(HOST, roomId);

        assertThat(snapshot.chats()).singleElement().satisfies(message -> {
            assertThat(message.actorId()).isEqualTo(HOST.actorId().value());
            assertThat(message.nickname()).isEqualTo(HOST.nickname());
            assertThat(message.body()).isEqualTo("복구할 메시지");
            assertThat(message.messageId()).isNotNull();
            assertThat(message.sentAt()).isNotNull();
        });
    }

    @Test
    void passwordRateLimitRunsBeforeTheSecondExpensiveHashVerification() {
        var repository = new InMemoryActiveRoomRepository();
        var encoder = new CountingPasswordEncoder();
        var limiter = new com.minigame.platform.shared.abuse.AbuseRateLimiter(
                Clock.systemUTC(), 10, Duration.ofMinutes(1), 1, Duration.ofMinutes(1)
        );
        var service = new RoomApplicationService(
                repository,
                encoder,
                new RecordingPublisher(),
                Clock.systemUTC(),
                new ChatPolicy(Clock.systemUTC()),
                limiter
        );
        var created = service.create(HOST, "비밀번호 비용 방", Visibility.PUBLIC, "1234", GameType.LIAR);

        assertThatThrownBy(() -> service.join(
                GUEST, new RoomCode(created.code()), "wrong",
                "00000000-0000-0000-0000-000000008013", "network-a"
        )).isInstanceOf(RoomRuleViolation.class).hasMessage("ROOM_PASSWORD_INVALID");
        assertThatThrownBy(() -> service.join(
                GUEST, new RoomCode(created.code()), "wrong",
                "00000000-0000-0000-0000-000000008014", "network-a"
        )).isInstanceOf(com.minigame.platform.shared.abuse.AbuseLimitExceededException.class);

        assertThat(encoder.matchesCalls).hasValue(1);
    }

    @Test
    void leaveDoesNotTurnSuccessfulMutationIntoRoomNotFound() {
        var repository = new EvictAfterMutationRepository();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder());
        var created = service.create(HOST, "퇴장 경쟁 방", Visibility.PUBLIC, null, GameType.LIAR);
        service.join(GUEST, new RoomCode(created.code()), null, REQUEST_ID);
        repository.evictAfterNextMutation = true;

        service.leave(
                GUEST,
                new RoomId(java.util.UUID.fromString(created.roomId())),
                "00000000-0000-0000-0000-000000008002"
        );

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void snapshotExposesStartEligibilityAfterAllNonHostParticipantsReady() {
        var repository = new InMemoryActiveRoomRepository();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder());
        var created = service.create(HOST, "시작 조건 방", Visibility.PUBLIC, null, GameType.LIAR);
        var roomId = new RoomId(java.util.UUID.fromString(created.roomId()));
        var guests = List.of(
                ActorPrincipal.guest(new ActorId("ready-guest-1"), "참가자1"),
                ActorPrincipal.guest(new ActorId("ready-guest-2"), "참가자2"),
                ActorPrincipal.guest(new ActorId("ready-guest-3"), "참가자3")
        );

        for (int index = 0; index < guests.size(); index++) {
            service.join(guests.get(index), new RoomCode(created.code()), null,
                    "00000000-0000-0000-0000-00000000802" + index);
        }
        for (int index = 0; index < guests.size(); index++) {
            service.changeReady(guests.get(index), roomId, true,
                    "00000000-0000-0000-0000-00000000803" + index);
        }

        assertThat(service.snapshot(HOST, roomId).canStart()).isTrue();
    }

    @Test
    void leavePublishesTheFreshStartEligibilityInItsPublicPayload() {
        var repository = new InMemoryActiveRoomRepository();
        var publisher = new RecordingPublisher();
        var service = new RoomApplicationService(repository, new TestPasswordEncoder(), publisher, Clock.systemUTC());
        var created = service.create(HOST, "퇴장 준비 상태 방", Visibility.PUBLIC, null, GameType.LIAR);
        var roomId = new RoomId(java.util.UUID.fromString(created.roomId()));
        var guest = ActorPrincipal.guest(new ActorId("leaving-guest"), "나가는 참가자");
        service.join(guest, new RoomCode(created.code()), null, "00000000-0000-0000-0000-000000008041");
        publisher.publicEvents.clear();

        service.leave(guest, roomId, "00000000-0000-0000-0000-000000008042");

        assertThat(publisher.publicEvents).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PLAYER_LEFT");
            assertThat(event.payload()).isEqualTo(Map.of("actorId", "leaving-guest", "canStart", false));
        });
    }

    @Test
    void closing_interrupt_failure_does_not_consume_leave_and_retry_can_close_room() {
        var repository = new InMemoryActiveRoomRepository();
        var games = mock(GameApplicationService.class);
        var service = new RoomApplicationService(
                repository,
                new TestPasswordEncoder(),
                new RecordingPublisher(),
                Clock.systemUTC(),
                new ChatPolicy(Clock.systemUTC()),
                new com.minigame.platform.shared.abuse.AbuseRateLimiter(
                        Clock.systemUTC(), Integer.MAX_VALUE, Duration.ofMinutes(1),
                        Integer.MAX_VALUE, Duration.ofMinutes(1)
                ),
                games
        );
        var created = service.create(HOST, "종료 재시도 방", Visibility.PRIVATE, null, GameType.LIAR);
        var roomId = new RoomId(java.util.UUID.fromString(created.roomId()));
        var requestId = "00000000-0000-0000-0000-000000008051";
        doThrow(new IllegalStateException("interrupt failed"))
                .doNothing()
                .when(games).roomClosed(any(Room.class), any());

        assertThatThrownBy(() -> service.leave(HOST, roomId, requestId))
                .isInstanceOf(IllegalStateException.class).hasMessage("interrupt failed");
        assertThat(repository.findById(roomId)).isPresent().get().satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(com.minigame.platform.room.domain.RoomStatus.WAITING);
            assertThat(snapshot.participants()).hasSize(1);
        });

        service.leave(HOST, roomId, requestId);
        assertThat(repository.findById(roomId)).isEmpty();
    }

    @Test
    void requester_game_projection_and_can_start_are_built_while_room_lock_is_held() {
        var repository = new LockAwareRepository();
        repository.save(RoomFixture.emptyRoom());
        var games = mock(GameApplicationService.class);
        when(games.snapshot(any(), any())).thenAnswer(invocation -> {
            assertThat(repository.insideLock).isTrue();
            return Optional.empty();
        });
        when(games.canStart(any())).thenAnswer(invocation -> {
            assertThat(repository.insideLock).isTrue();
            return false;
        });
        var service = new RoomApplicationService(
                repository,
                new TestPasswordEncoder(),
                new RecordingPublisher(),
                Clock.systemUTC(),
                new ChatPolicy(Clock.systemUTC()),
                new com.minigame.platform.shared.abuse.AbuseRateLimiter(
                        Clock.systemUTC(), Integer.MAX_VALUE, Duration.ofMinutes(1),
                        Integer.MAX_VALUE, Duration.ofMinutes(1)
                ),
                games
        );

        service.snapshot(
                ActorPrincipal.guest(RoomFixture.HOST, "방장"),
                RoomFixture.ROOM_ID
        );
    }

    private static class DelegatingRepository implements ActiveRoomRepository {
        final InMemoryActiveRoomRepository delegate = new InMemoryActiveRoomRepository();

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
            return delegate.withRoom(roomId, command);
        }

        @Override
        public void remove(RoomId roomId) {
            delegate.remove(roomId);
        }
    }

    private static final class LockAwareRepository extends DelegatingRepository {
        private final AtomicBoolean insideLock = new AtomicBoolean();

        @Override
        public RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command) {
            return super.withRoom(roomId, room -> {
                insideLock.set(true);
                try {
                    return command.apply(room);
                } finally {
                    insideLock.set(false);
                }
            });
        }
    }

    private static final class SaveBarrierRepository extends DelegatingRepository {
        private final CountDownLatch saved = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void save(Room room) {
            super.save(room);
            saved.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("save release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static final class FailingSaveRepository extends DelegatingRepository {
        private Room captured;

        @Override
        public void save(Room room) {
            captured = room;
            super.save(room);
            throw new IllegalStateException("save failed");
        }

        void publishCapturedRoom() {
            delegate.save(captured);
        }
    }

    private static final class EvictAfterMutationRepository extends DelegatingRepository {
        private boolean evictAfterNextMutation;

        @Override
        public RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command) {
            var result = super.withRoom(roomId, command);
            if (evictAfterNextMutation) {
                evictAfterNextMutation = false;
                remove(roomId);
            }
            return result;
        }
    }

    private static final class TestPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword.equals(encode(rawPassword));
        }
    }

    private static final class CountingPasswordEncoder implements PasswordEncoder {
        private final AtomicInteger matchesCalls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.incrementAndGet();
            return encodedPassword.equals(encode(rawPassword));
        }
    }

    private static final class RecordingPublisher implements RoomEventPublisher {
        private final List<EventEnvelope<?>> publicEvents = new ArrayList<>();
        private final List<EventEnvelope<?>> lobbyEvents = new ArrayList<>();

        @Override
        public void publishPublic(EventEnvelope<?> event) {
            publicEvents.add(event);
        }

        @Override
        public void publishPrivate(String userName, EventEnvelope<?> event) {
        }

        @Override
        public void publishLobby(EventEnvelope<?> event) {
            lobbyEvents.add(event);
        }
    }
}
