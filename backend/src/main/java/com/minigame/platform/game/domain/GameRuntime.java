package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.room.domain.GameType;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable session-scoped game data. Its methods synchronize the small mutable
 * surface so it remains safe if a caller ever reaches it outside the room lock.
 */
public final class GameRuntime {
    public static final int MAX_PROCESSED_REQUEST_IDS = 1_024;

    private final UUID sessionId;
    private final GameType gameType;
    private GameState state;
    private final Map<ActorId, Integer> scores = new LinkedHashMap<>();
    private final Set<UUID> usedContentIds = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> processedRequestIds = new LinkedHashSet<>();

    public GameRuntime(UUID sessionId, GameType gameType, GameState state, List<GamePlayer> players) {
        this(sessionId, gameType, state, players, Set.of());
    }

    public GameRuntime(
            UUID sessionId,
            GameType gameType,
            GameState state,
            List<GamePlayer> players,
            Collection<UUID> usedContentIds
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.gameType = Objects.requireNonNull(gameType, "gameType");
        this.state = Objects.requireNonNull(state, "state");
        var playerIds = new HashSet<ActorId>();
        for (var player : Objects.requireNonNull(players, "players")) {
            var actorId = Objects.requireNonNull(player, "player").actorId();
            if (!playerIds.add(actorId)) {
                throw new IllegalArgumentException("players must have unique actor IDs");
            }
            scores.put(actorId, 0);
        }
        this.usedContentIds.addAll(Objects.requireNonNull(usedContentIds, "usedContentIds"));
    }

    public UUID sessionId() {
        return sessionId;
    }

    public GameType gameType() {
        return gameType;
    }

    public synchronized GameState state() {
        return state;
    }

    public synchronized void replaceState(GameState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public synchronized Map<ActorId, Integer> scores() {
        return Map.copyOf(scores);
    }

    /**
     * Adds newly active players to the cumulative scoreboard at zero without
     * dropping departed players, whose scores remain required for final ranks.
     */
    public synchronized void synchronizePlayers(List<GamePlayer> players) {
        var playerIds = new HashSet<ActorId>();
        for (var player : Objects.requireNonNull(players, "players")) {
            var actorId = Objects.requireNonNull(player, "player").actorId();
            if (!playerIds.add(actorId)) {
                throw new IllegalArgumentException("players must have unique actor IDs");
            }
            scores.putIfAbsent(actorId, 0);
        }
    }

    public synchronized void applyScoreDeltas(Map<ActorId, Integer> scoreDeltas) {
        Objects.requireNonNull(scoreDeltas, "scoreDeltas").forEach((actorId, delta) -> {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(delta, "delta");
            scores.merge(actorId, delta, Integer::sum);
        });
    }

    public synchronized Set<UUID> usedContentIds() {
        return Set.copyOf(usedContentIds);
    }

    public synchronized boolean recordUsedContent(UUID contentId) {
        return usedContentIds.add(Objects.requireNonNull(contentId, "contentId"));
    }

    public synchronized boolean markRequestProcessed(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (!processedRequestIds.add(requestId)) {
            return false;
        }
        if (processedRequestIds.size() > MAX_PROCESSED_REQUEST_IDS) {
            processedRequestIds.remove(processedRequestIds.getFirst());
        }
        return true;
    }

    public synchronized boolean hasProcessedRequest(UUID requestId) {
        return processedRequestIds.contains(Objects.requireNonNull(requestId, "requestId"));
    }

    public synchronized int processedRequestCount() {
        return processedRequestIds.size();
    }
}
