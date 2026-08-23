package com.minigame.platform.room.adapter.out.memory;

import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

public final class InMemoryActiveRoomRepository implements ActiveRoomRepository {
    private final ConcurrentHashMap<RoomId, RoomHandle> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RoomCode, RoomId> codeIndex = new ConcurrentHashMap<>();
    private final Supplier<String> codeSupplier;

    public InMemoryActiveRoomRepository() {
        var random = new SecureRandom();
        this.codeSupplier = () -> "%06d".formatted(random.nextInt(1_000_000));
    }

    InMemoryActiveRoomRepository(Supplier<String> codeSupplier) {
        this.codeSupplier = codeSupplier;
    }

    @Override
    public void save(Room room) {
        var snapshot = room.snapshot();
        var indexedRoomId = codeIndex.putIfAbsent(snapshot.code(), snapshot.id());
        if (indexedRoomId != null && !indexedRoomId.equals(snapshot.id())) {
            throw new RoomRuleViolation("ROOM_CODE_CONFLICT");
        }
        var previous = rooms.putIfAbsent(snapshot.id(), new RoomHandle(room));
        if (previous != null && previous.room() != room) {
            if (indexedRoomId == null) {
                codeIndex.remove(snapshot.code(), snapshot.id());
            }
            throw new RoomRuleViolation("ROOM_ALREADY_EXISTS");
        }
    }

    @Override
    public Optional<Room> findById(RoomId roomId) {
        return Optional.ofNullable(rooms.get(roomId)).map(RoomHandle::room);
    }

    @Override
    public Optional<Room> findByCode(RoomCode code) {
        var roomId = codeIndex.get(code);
        return roomId == null ? Optional.empty() : findById(roomId);
    }

    @Override
    public List<Room> findAll() {
        return rooms.values().stream().map(RoomHandle::room).toList();
    }

    @Override
    public RoomCode generateCode() {
        while (true) {
            var candidate = new RoomCode(codeSupplier.get());
            if (!codeIndex.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public <T> T withRoom(RoomId roomId, Function<Room, T> command) {
        var handle = rooms.get(roomId);
        if (handle == null) {
            throw new RoomRuleViolation("ROOM_NOT_FOUND");
        }
        handle.lock().lock();
        try {
            return command.apply(handle.room());
        } finally {
            handle.lock().unlock();
        }
    }

    @Override
    public void remove(RoomId roomId) {
        var handle = rooms.remove(roomId);
        if (handle != null) {
            codeIndex.remove(handle.room().snapshot().code(), roomId);
        }
    }

    private record RoomHandle(Room room, ReentrantLock lock) {
        private RoomHandle(Room room) {
            this(room, new ReentrantLock());
        }
    }
}
