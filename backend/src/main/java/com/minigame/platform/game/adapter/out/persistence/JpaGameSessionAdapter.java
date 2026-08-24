package com.minigame.platform.game.adapter.out.persistence;

import com.minigame.platform.game.application.GameSessionPort;
import com.minigame.platform.game.application.GameSessionNotRunningException;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaGameSessionAdapter implements GameSessionPort {
    private final ObjectProvider<EntityManager> entityManagers;

    public JpaGameSessionAdapter(ObjectProvider<EntityManager> entityManagers) {
        this.entityManagers = entityManagers;
    }

    @Override
    @Transactional
    public UUID start(StartGameSession command) {
        entityManager().persist(GameSessionEntity.running(
                command.sessionId(),
                command.roomId(),
                command.gameType(),
                command.settingsJson(),
                command.startedAt()
        ));
        return command.sessionId();
    }

    @Override
    @Transactional
    public void complete(UUID sessionId, List<GameParticipantResult> results, Instant endedAt) {
        var entityManager = entityManager();
        var transitioned = entityManager.createQuery("""
                        update GameSessionEntity session
                           set session.status = 'COMPLETED',
                               session.endedAt = :endedAt
                         where session.id = :sessionId
                           and session.status = 'RUNNING'
                        """)
                .setParameter("endedAt", endedAt)
                .setParameter("sessionId", sessionId)
                .executeUpdate();
        if (transitioned != 1) {
            throw new GameSessionNotRunningException(sessionId);
        }
        var session = entityManager.getReference(GameSessionEntity.class, sessionId);
        results.forEach(result -> entityManager.persist(GameParticipantEntity.from(
                session,
                result.actorId(),
                result.nickname(),
                result.score(),
                result.rank(),
                result.roundsPlayed()
        )));
    }

    @Override
    @Transactional
    public int interruptRunning(Instant interruptedAt) {
        return entityManager().createQuery("""
                        update GameSessionEntity session
                           set session.status = 'INTERRUPTED',
                               session.endedAt = :interruptedAt
                         where session.status = 'RUNNING'
                        """)
                .setParameter("interruptedAt", interruptedAt)
                .executeUpdate();
    }

    private EntityManager entityManager() {
        return entityManagers.getObject();
    }
}
