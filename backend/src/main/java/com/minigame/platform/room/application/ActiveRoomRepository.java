package com.minigame.platform.room.application;

import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomEvent;
import com.minigame.platform.room.domain.RoomId;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface ActiveRoomRepository {
    void save(Room room);

    Optional<Room.Snapshot> findById(RoomId roomId);

    Optional<Room.Snapshot> findByCode(RoomCode code);

    List<Room.Snapshot> findAll();

    RoomCode generateCode();

    RoomMutationResult withRoom(RoomId roomId, Function<Room, List<RoomEvent>> command);

    default <T> LockedRoomResult<T> withRoomValue(RoomId roomId, Function<Room, T> command) {
        var value = new java.util.concurrent.atomic.AtomicReference<T>();
        var result = withRoom(roomId, room -> {
            value.set(command.apply(room));
            return List.of();
        });
        return new LockedRoomResult<>(value.get(), result.snapshot());
    }

    void remove(RoomId roomId);
}
