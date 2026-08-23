package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void existingParticipantCanRejoinWithTheRoomPasswordWithoutMutatingOrRepublishing() {
        var repository = new InMemoryActiveRoomRepository();
        var publisher = new RecordingPublisher();
        var service = new RoomApplicationService(
                repository, new TestPasswordEncoder(), publisher, Clock.systemUTC()
        );
        var created = service.create(HOST, "재접속 비밀방", Visibility.PUBLIC, "1234", GameType.LIAR);

        assertThatThrownBy(() -> service.join(
                HOST, new RoomCode(created.code()), "wrong",
                "00000000-0000-0000-0000-000000008010"
        )).isInstanceOf(RoomRuleViolation.class).hasMessage("ROOM_PASSWORD_INVALID");

        var rejoined = service.join(
                HOST, new RoomCode(created.code()), "1234",
                "00000000-0000-0000-0000-000000008011"
        );

        assertThat(rejoined.sequence()).isZero();
        assertThat(rejoined.participantCount()).isEqualTo(1);
        assertThat(rejoined.participants()).singleElement()
                .extracting(RoomApplicationService.ParticipantView::actorId)
                .isEqualTo(HOST.actorId().value());
        assertThat(publisher.publicEvents).isEmpty();
        assertThat(publisher.lobbyEvents).hasSize(1);
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
