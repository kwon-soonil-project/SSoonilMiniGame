package com.minigame.platform.shared.realtime;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.RoomId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(
        int version,
        UUID eventId,
        String requestId,
        UUID roomId,
        String actorId,
        String type,
        long sequence,
        Instant occurredAt,
        T payload
) {
    public EventEnvelope {
        if (version != 1) {
            throw new IllegalArgumentException("version");
        }
        Objects.requireNonNull(eventId, "eventId");
        requestId = canonicalRequestId(requestId);
        Objects.requireNonNull(roomId, "roomId");
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    public static <T> EventEnvelope<T> create(
            String requestId,
            RoomId roomId,
            ActorPrincipal actor,
            String type,
            long sequence,
            Clock clock,
            T payload
    ) {
        return new EventEnvelope<>(
                1,
                UUID.randomUUID(),
                requestId,
                roomId.value(),
                actor.actorId().value(),
                type,
                sequence,
                clock.instant(),
                payload
        );
    }

    private static String canonicalRequestId(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("requestId");
        }
        try {
            var parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("requestId");
            }
            return parsed.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("requestId", exception);
        }
    }
}
