package com.minigame.platform.game.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "game_participants")
public class GameParticipantEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private GameSessionEntity session;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private int score;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "rounds_played", nullable = false)
    private int roundsPlayed;

    protected GameParticipantEntity() {
    }

    private GameParticipantEntity(UUID id, GameSessionEntity session, UUID actorId, String nickname, int score, int rank, int roundsPlayed) {
        this.id = id;
        this.session = session;
        this.actorId = actorId;
        this.nickname = nickname;
        this.score = score;
        this.rank = rank;
        this.roundsPlayed = roundsPlayed;
    }

    public static GameParticipantEntity from(
            GameSessionEntity session,
            UUID actorId,
            String nickname,
            int score,
            int rank,
            int roundsPlayed
    ) {
        return new GameParticipantEntity(UUID.randomUUID(), session, actorId, nickname, score, rank, roundsPlayed);
    }
}
