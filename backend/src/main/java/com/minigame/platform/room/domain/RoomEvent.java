package com.minigame.platform.room.domain;

import com.minigame.platform.auth.domain.ActorId;

public sealed interface RoomEvent {
    long sequence();

    record ParticipantJoined(long sequence, Participant participant, boolean canStart) implements RoomEvent {
    }

    record ReadyChanged(long sequence, ActorId actorId, boolean ready, boolean canStart) implements RoomEvent {
    }

    record SettingsUpdated(long sequence, RoomSettings settings, boolean canStart) implements RoomEvent {
    }

    record HostTransferred(long sequence, ActorId previousHostId, ActorId newHostId, boolean canStart) implements RoomEvent {
    }

    record ParticipantLeft(long sequence, ActorId actorId, boolean canStart) implements RoomEvent {
    }

    record RoomClosed(long sequence) implements RoomEvent {
    }

    record ChatAccepted(long sequence) implements RoomEvent {
    }

    record GameStateChanged(long sequence) implements RoomEvent {
    }

    record PlayerSpectatorChanged(long sequence, ActorId actorId, boolean spectator) implements RoomEvent {
    }
}
