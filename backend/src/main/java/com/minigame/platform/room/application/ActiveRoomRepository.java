package com.minigame.platform.room.application;

import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface ActiveRoomRepository {
    void save(Room room);

    Optional<Room> findById(RoomId roomId);

    Optional<Room> findByCode(RoomCode code);

    List<Room> findAll();

    RoomCode generateCode();

    <T> T withRoom(RoomId roomId, Function<Room, T> command);

    void remove(RoomId roomId);
}
