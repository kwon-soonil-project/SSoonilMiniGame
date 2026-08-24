package com.minigame.platform.game.adapter.out.persistence;

import com.minigame.platform.room.domain.GameType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {
    @Id
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false)
    private GameType gameType;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String settings;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected GameSessionEntity() {
    }

    private GameSessionEntity(UUID id, UUID roomId, GameType gameType, String settings, Instant startedAt) {
        this.id = id;
        this.roomId = roomId;
        this.gameType = gameType;
        this.status = "RUNNING";
        this.settings = settings;
        this.startedAt = startedAt;
    }

    public static GameSessionEntity running(UUID id, UUID roomId, GameType gameType, String settings, Instant startedAt) {
        return new GameSessionEntity(id, roomId, gameType, settings, startedAt);
    }

    public void complete(Instant endedAt) {
        this.status = "COMPLETED";
        this.endedAt = endedAt;
    }

    public UUID getId() {
        return id;
    }
}
