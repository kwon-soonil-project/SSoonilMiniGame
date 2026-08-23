package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;

public sealed interface RoomEvent {
    long sequence();

    record ParticipantJoined(long sequence, Participant participant) implements RoomEvent {
    }

    record ReadyChanged(long sequence, ActorId actorId, boolean ready) implements RoomEvent {
    }

    record SettingsUpdated(long sequence, RoomSettings settings) implements RoomEvent {
    }

    record HostTransferred(long sequence, ActorId previousHostId, ActorId newHostId) implements RoomEvent {
    }

    record ParticipantLeft(long sequence, ActorId actorId) implements RoomEvent {
    }

    record RoomClosed(long sequence) implements RoomEvent {
    }
}
