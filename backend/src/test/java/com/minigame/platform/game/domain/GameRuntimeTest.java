package com.minigame.platform.game.domain;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.room.domain.GameType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GameRuntimeTest {
    private static final ActorId PLAYER_ID = new ActorId("runtime-player");

    @Test
    void retains_the_newest_1024_unique_request_ids_in_fifo_order() {
        var runtime = runtime();
        var requestIds = java.util.stream.LongStream.range(0, 1_025)
                .mapToObj(index -> new UUID(0, index))
                .toList();

        requestIds.subList(0, 1_024).forEach(requestId -> assertThat(runtime.markRequestProcessed(requestId)).isTrue());
        assertThat(runtime.markRequestProcessed(requestIds.getFirst())).isFalse();

        assertThat(runtime.markRequestProcessed(requestIds.getLast())).isTrue();
        assertThat(runtime.processedRequestCount()).isEqualTo(1_024);
        assertThat(runtime.hasProcessedRequest(requestIds.getFirst())).isFalse();
        assertThat(runtime.hasProcessedRequest(requestIds.get(1))).isTrue();

        assertThat(runtime.markRequestProcessed(requestIds.getFirst())).isTrue();
        assertThat(runtime.hasProcessedRequest(requestIds.get(1))).isFalse();
        assertThat(runtime.hasProcessedRequest(requestIds.getFirst())).isTrue();
    }

    @Test
    void synchronizes_concurrent_request_and_score_updates() throws Exception {
        var runtime = runtime();
        var sharedRequest = new UUID(0, 99);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 128)
                    .<Callable<Boolean>>mapToObj(ignored -> () -> {
                        runtime.applyScoreDeltas(Map.of(PLAYER_ID, 1));
                        return runtime.markRequestProcessed(sharedRequest);
                    })
                    .toList());

            var firstMarks = 0;
            for (var result : results) {
                if (result.get()) {
                    firstMarks++;
                }
            }
            assertThat(firstMarks).isEqualTo(1);
            assertThat(runtime.scores()).containsEntry(PLAYER_ID, 128);
            assertThat(runtime.processedRequestCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returns_immutable_score_and_content_snapshots() {
        var runtime = runtime();
        var contentId = new UUID(0, 123);
        runtime.applyScoreDeltas(Map.of(PLAYER_ID, 3));
        runtime.recordUsedContent(contentId);

        var scores = runtime.scores();
        var contentIds = runtime.usedContentIds();

        assertThatUnsupported(() -> scores.put(PLAYER_ID, 99));
        assertThatUnsupported(() -> contentIds.add(UUID.randomUUID()));
        assertThat(runtime.scores()).containsEntry(PLAYER_ID, 3);
        assertThat(runtime.usedContentIds()).containsExactly(contentId);
    }

    @Test
    void rejects_duplicate_or_missing_player_input() {
        var player = new GamePlayer(PLAYER_ID, "runtime");

        assertThatIllegalArgumentException().isThrownBy(() -> new GameRuntime(
                UUID.randomUUID(), GameType.LIAR, new TestState(), List.of(player, player)
        ));
        assertThatNullPointerException().isThrownBy(() -> new GameRuntime(
                UUID.randomUUID(), GameType.LIAR, new TestState(), null
        ));
    }

    @Test
    void synchronizing_players_adds_newcomers_at_zero_and_retains_departed_scores_for_ranking() {
        var runtime = runtime();
        var departed = new ActorId("departed");
        var newcomer = new ActorId("newcomer");
        runtime.applyScoreDeltas(Map.of(PLAYER_ID, 3, departed, 2));

        runtime.synchronizePlayers(List.of(new GamePlayer(PLAYER_ID, "runtime"), new GamePlayer(newcomer, "new")));

        assertThat(runtime.scores()).containsEntry(PLAYER_ID, 3).containsEntry(departed, 2).containsEntry(newcomer, 0);
    }

    @Test
    void immutable_snapshot_does_not_change_when_live_runtime_advances_after_unlock() {
        var runtime = runtime();
        var snapshot = runtime.snapshot();

        runtime.replaceState(new AdvancedState());
        runtime.applyScoreDeltas(Map.of(PLAYER_ID, 7));

        assertThat(snapshot.state()).isInstanceOf(TestState.class);
        assertThat(snapshot.scores()).containsEntry(PLAYER_ID, 0);
        assertThat(snapshot.scores()).isNotSameAs(runtime.scores());
    }

    private static GameRuntime runtime() {
        return new GameRuntime(
                new UUID(0, 1),
                GameType.LIAR,
                new TestState(),
                List.of(new GamePlayer(PLAYER_ID, "runtime"))
        );
    }

    private static void assertThatUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable mutation) {
        org.assertj.core.api.Assertions.assertThatThrownBy(mutation)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private record TestState() implements GameState {
    }

    private record AdvancedState() implements GameState {
    }
}
