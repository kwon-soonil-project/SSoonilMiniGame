package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;

import java.util.UUID;

public final class RoomFixture {
    public static final ActorId HOST = new ActorId("host-1");
    public static final ActorId GUEST_1 = new ActorId("guest-1");
    public static final ActorId GUEST_2 = new ActorId("guest-2");
    public static final ActorId GUEST_3 = new ActorId("guest-3");
    public static final RoomId ROOM_ID = new RoomId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    public static final RoomCode ROOM_CODE = new RoomCode("482193");

    private RoomFixture() {
    }

    public static Room emptyRoom() {
        return Room.create(
            ROOM_ID,
            ROOM_CODE,
            "퇴근 후 딱 한 판!",
            Visibility.PUBLIC,
            new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "all"),
            HOST,
            "방장"
        );
    }

    public static Room roomWithFourParticipants() {
        var room = emptyRoom();
        room.join(GUEST_1, "참가자1", false, "req-join-1");
        room.join(GUEST_2, "참가자2", false, "req-join-2");
        room.join(GUEST_3, "참가자3", false, "req-join-3");
        return room;
    }
}
