package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomPresenceServiceTest {
    private static final ActorPrincipal HOST = ActorPrincipal.guest(new ActorId("presence-host"), "방장감자");
    private static final ActorPrincipal GUEST = ActorPrincipal.guest(new ActorId("presence-guest"), "참가감자");

    @Test
    void onlyTheLastSessionDisconnectSchedulesLeaveThroughTheRoomBoundary() {
        var fixture = fixture();
        fixture.presence.connected("tab-1", HOST);
        fixture.presence.connected("tab-2", HOST);

        fixture.presence.disconnected("tab-1");
        assertThat(fixture.scheduler.pending()).isZero();

        fixture.presence.disconnected("tab-2");
        assertThat(fixture.scheduler.pending()).isOne();
        fixture.scheduler.runPending();

        var snapshot = fixture.repository.findById(fixture.roomId).orElseThrow();
        assertThat(snapshot.participants()).singleElement()
                .extracting(participant -> participant.actorId().value())
                .isEqualTo(GUEST.actorId().value());
        assertThat(snapshot.hostId()).isEqualTo(GUEST.actorId());
        assertThat(fixture.publisher.publicTypes())
                .containsExactly("PLAYER_JOINED", "PLAYER_LEFT", "HOST_TRANSFERRED");
        assertThat(fixture.publisher.lobbyEvents).hasSize(3);
    }

    @Test
    void reconnectWithinGraceCancelsThePendingLeave() {
        var fixture = fixture();
        fixture.presence.connected("old-session", HOST);
        fixture.presence.disconnected("old-session");

        fixture.presence.connected("new-session", HOST);
        fixture.scheduler.runPending();

        assertThat(fixture.repository.findById(fixture.roomId).orElseThrow().participants())
                .extracting(participant -> participant.actorId().value())
                .contains(HOST.actorId().value());
    }

    @Test
    void graceExpiryRemovesAnEmptyRoomAndPublishesItsLobbyRemoval() {
        var repository = new InMemoryActiveRoomRepository();
        var publisher = new RecordingPublisher();
        var rooms = new RoomApplicationService(repository, new PlainEncoder(), publisher, Clock.systemUTC());
        var created = rooms.create(HOST, "혼자 남은 방", Visibility.PUBLIC, null, GameType.LIAR);
        var roomId = new RoomId(java.util.UUID.fromString(created.roomId()));
        var scheduler = new ManualScheduler();
        var presence = new RoomPresenceService(rooms, scheduler, Duration.ofSeconds(30));
        presence.connected("only-session", HOST);

        presence.disconnected("only-session");
        scheduler.runPending();

        assertThat(repository.findById(roomId)).isEmpty();
        assertThat(publisher.lobbyEvents.getLast().type()).isEqualTo("LOBBY_ROOM_REMOVE");
    }

    private static Fixture fixture() {
        var repository = new InMemoryActiveRoomRepository();
        var publisher = new RecordingPublisher();
        var rooms = new RoomApplicationService(repository, new PlainEncoder(), publisher, Clock.systemUTC());
        var created = rooms.create(HOST, "연결 수명 방", Visibility.PUBLIC, null, GameType.LIAR);
        rooms.join(GUEST, new RoomCode(created.code()), null, "00000000-0000-0000-0000-000000008101");
        var scheduler = new ManualScheduler();
        return new Fixture(
                repository,
                publisher,
                new RoomId(java.util.UUID.fromString(created.roomId())),
                scheduler,
                new RoomPresenceService(rooms, scheduler, Duration.ofSeconds(30))
        );
    }

    private record Fixture(
            InMemoryActiveRoomRepository repository,
            RecordingPublisher publisher,
            RoomId roomId,
            ManualScheduler scheduler,
            RoomPresenceService presence
    ) {
    }

    private static final class ManualScheduler implements RoomPresenceService.DisconnectScheduler {
        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public RoomPresenceService.Cancellation schedule(Duration delay, Runnable task) {
            var scheduled = new Scheduled(delay, task);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        int pending() {
            return (int) tasks.stream().filter(task -> !task.cancelled).count();
        }

        void runPending() {
            List.copyOf(tasks).forEach(task -> {
                if (!task.cancelled) task.runnable.run();
            });
            tasks.clear();
        }

        private static final class Scheduled {
            private final Duration delay;
            private final Runnable runnable;
            private boolean cancelled;

            private Scheduled(Duration delay, Runnable runnable) {
                assertThat(delay).isEqualTo(Duration.ofSeconds(30));
                this.delay = delay;
                this.runnable = runnable;
            }
        }
    }

    private static final class PlainEncoder implements PasswordEncoder {
        @Override public String encode(CharSequence rawPassword) { return "encoded:" + rawPassword; }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword.equals(encode(rawPassword));
        }
    }

    private static final class RecordingPublisher implements RoomEventPublisher {
        private final List<EventEnvelope<?>> publicEvents = new ArrayList<>();
        private final List<EventEnvelope<?>> lobbyEvents = new ArrayList<>();

        @Override public void publishPublic(EventEnvelope<?> event) { publicEvents.add(event); }
        @Override public void publishPrivate(String userName, EventEnvelope<?> event) { }
        @Override public void publishLobby(EventEnvelope<?> event) { lobbyEvents.add(event); }

        List<String> publicTypes() { return publicEvents.stream().map(EventEnvelope::type).toList(); }
    }
}
