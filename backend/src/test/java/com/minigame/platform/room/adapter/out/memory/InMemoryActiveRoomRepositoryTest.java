package com.minigame.platform.room.adapter.out.memory;

import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomFixture;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryActiveRoomRepositoryTest {
    @Test
    void findsRoomByStableCode() {
        var repository = new InMemoryActiveRoomRepository();
        repository.save(RoomFixture.emptyRoom());

        var byCode = repository.findByCode(new RoomCode("482193")).orElseThrow();
        var byId = repository.findById(RoomFixture.ROOM_ID).orElseThrow();

        assertThat(byCode.id()).isEqualTo(RoomFixture.ROOM_ID);
        assertThat(byId.code()).isEqualTo(RoomFixture.ROOM_CODE);
        assertThat(repository.findAll()).containsExactly(byId);
    }

    @Test
    void removingRoomClearsTheRoomAndCodeIndexes() {
        var repository = new InMemoryActiveRoomRepository();
        repository.save(RoomFixture.emptyRoom());

        repository.remove(RoomFixture.ROOM_ID);

        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.findById(RoomFixture.ROOM_ID)).isEmpty();
        assertThat(repository.findByCode(RoomFixture.ROOM_CODE)).isEmpty();
    }

    @Test
    void generatedCodeIsSixDigitsAndRetriesCollisions() {
        var repository = new InMemoryActiveRoomRepository(new SequenceCodeSupplier("482193", "000042"));
        repository.save(RoomFixture.emptyRoom());

        assertThat(repository.generateCode()).isEqualTo(new RoomCode("000042"));
    }

    @Test
    void rejectsSavingTwoRoomsWithTheSameCode() {
        var repository = new InMemoryActiveRoomRepository();
        repository.save(RoomFixture.emptyRoom());
        var otherRoom = com.minigame.platform.room.domain.Room.create(
            new RoomId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            RoomFixture.ROOM_CODE,
            "다른 방",
            com.minigame.platform.room.domain.Visibility.PUBLIC,
            RoomFixture.emptyRoom().snapshot().settings(),
            new com.minigame.platform.auth.domain.ActorId("other-host"),
            "다른 방장"
        );

        assertThatThrownBy(() -> repository.save(otherRoom))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_CODE_CONFLICT");
    }

    @Test
    void serializesConcurrentChangesWithinOneRoom() throws Exception {
        var repository = new InMemoryActiveRoomRepository();
        repository.save(RoomFixture.emptyRoom());
        var simultaneousCallbacks = new AtomicInteger();
        var maximumSimultaneousCallbacks = new AtomicInteger();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    repository.withRoom(RoomFixture.ROOM_ID, room -> {
                        int active = simultaneousCallbacks.incrementAndGet();
                        maximumSimultaneousCallbacks.accumulateAndGet(active, Math::max);
                        Thread.yield();
                        simultaneousCallbacks.decrementAndGet();
                        return java.util.List.of();
                    });
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(maximumSimultaneousCallbacks).hasValue(1);
    }

    @Test
    void snapshotReadWaitsForAnInFlightChangeAndReturnsItsCompletedState() throws Exception {
        var repository = new InMemoryActiveRoomRepository();
        var initialRoom = RoomFixture.emptyRoom();
        initialRoom.join(RoomFixture.GUEST_1, "참가자1", false, RoomFixture.requestId("repository-join"));
        repository.save(initialRoom);
        var changed = new CountDownLatch(1);
        var releaseChange = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var change = executor.submit(() -> repository.withRoom(RoomFixture.ROOM_ID, room -> {
                var events = room.changeReady(
                    RoomFixture.GUEST_1,
                    true,
                    RoomFixture.requestId("repository-ready")
                );
                changed.countDown();
                try {
                    if (!releaseChange.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("change release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return events;
            }));
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();

            var read = executor.submit(() -> repository.findById(RoomFixture.ROOM_ID).orElseThrow());
            assertThatThrownBy(() -> read.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseChange.countDown();
            change.get(5, TimeUnit.SECONDS);
            var snapshot = read.get(5, TimeUnit.SECONDS);
            assertThat(snapshot.sequence()).isEqualTo(2);
            assertThat(snapshot.participants())
                .filteredOn(participant -> participant.actorId().equals(RoomFixture.GUEST_1))
                .allMatch(com.minigame.platform.room.domain.Participant::ready);
        }
    }

    @Test
    void rejectsCommandsForAnUnknownRoom() {
        var repository = new InMemoryActiveRoomRepository();

        assertThatThrownBy(() -> repository.withRoom(RoomFixture.ROOM_ID, room -> null))
            .isInstanceOf(RoomRuleViolation.class)
            .hasMessageContaining("ROOM_NOT_FOUND");
    }

    private static final class SequenceCodeSupplier implements java.util.function.Supplier<String> {
        private final String[] values;
        private int index;

        private SequenceCodeSupplier(String... values) {
            this.values = values;
        }

        @Override
        public String get() {
            return values[index++];
        }
    }
}
