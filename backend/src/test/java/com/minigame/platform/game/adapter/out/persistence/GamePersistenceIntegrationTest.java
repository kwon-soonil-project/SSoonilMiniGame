package com.minigame.platform.game.adapter.out.persistence;

import com.minigame.platform.game.application.GameSessionPort;
import com.minigame.platform.game.application.GameSessionNotRunningException;
import com.minigame.platform.game.application.LiarContentPort;
import com.minigame.platform.room.domain.GameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long")
@Testcontainers
class GamePersistenceIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine")
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LiarContentPort liarContent;

    @Autowired
    private GameSessionPort gameSessions;

    @BeforeEach
    void isolateGameSessionState() {
        jdbc.update("delete from game_participants");
        jdbc.update("delete from game_sessions");
    }

    @Test
    void migrations_seed_eight_liar_categories_with_fifty_unique_items_each() {
        var counts = jdbc.queryForList("""
                select p.code, count(*) item_count, count(distinct i.normalized_value) unique_count
                  from content_packs p join content_items i on i.pack_id = p.id
                 where p.game_type = 'LIAR' and p.active and i.active
                 group by p.code order by p.code
                """);

        assertThat(counts).hasSize(8);
        assertThat(counts).allSatisfy(row -> {
            assertThat(row.get("item_count")).isEqualTo(50L);
            assertThat(row.get("unique_count")).isEqualTo(50L);
        });
        assertThat(jdbc.queryForObject("""
                select count(distinct i.normalized_value)
                  from content_packs p join content_items i on i.pack_id = p.id
                 where p.game_type = 'LIAR' and p.active and i.active
                """, Long.class)).isEqualTo(400L);
    }

    @Test
    void content_selection_excludes_previously_used_words_and_reports_remaining_capacity() {
        var firstSelection = liarContent.select("food", Set.of(), 2);
        var excluded = firstSelection.stream().map(word -> word.id()).collect(Collectors.toSet());

        var nextSelection = liarContent.select("food", excluded, 48);

        assertThat(firstSelection).hasSize(2);
        assertThat(nextSelection).hasSize(48);
        assertThat(nextSelection).extracting(word -> word.id()).doesNotContainAnyElementsOf(excluded);
        assertThat(liarContent.available("food", excluded, 48)).isTrue();
        assertThat(liarContent.available("food", excluded, 49)).isFalse();
    }

    @Test
    void all_category_selection_spans_active_liar_packs_and_respects_exclusions_and_limit() {
        var excluded = liarContent.select("all", Set.of(), 6).stream()
                .map(word -> word.id())
                .collect(Collectors.toSet());

        var selected = liarContent.select("all", excluded, 400);

        assertThat(excluded).hasSize(6);
        assertThat(selected).hasSize(394);
        assertThat(selected).extracting(word -> word.id())
                .doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(excluded);
        assertThat(selected.stream().map(word -> word.categoryCode()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("animal", "food", "hobby", "household", "job", "place", "sports", "transport");
        assertThat(liarContent.available("all", excluded, 394)).isTrue();
        assertThat(liarContent.available("all", excluded, 395)).isFalse();
    }

    @Test
    void content_selection_preserves_non_empty_postgres_aliases() {
        var aliasItemId = UUID.randomUUID();
        var foodPackId = jdbc.queryForObject(
                "select id from content_packs where code = 'food'",
                UUID.class
        );
        jdbc.update("""
                        insert into content_items (id, pack_id, value, normalized_value, aliases, active)
                        values (?, ?, '붕어 빵', '붕어빵', array['잉어빵', '팥빵']::text[], true)
                        """,
                aliasItemId,
                foodPackId
        );

        try {
            var word = liarContent.select("food", Set.of(), 51).stream()
                    .filter(candidate -> candidate.id().equals(aliasItemId))
                    .findFirst()
                    .orElseThrow();

            assertThat(word.answer()).isEqualTo("붕어 빵");
            assertThat(word.aliases()).containsExactlyInAnyOrder("잉어빵", "팥빵");
        } finally {
            jdbc.update("delete from content_items where id = ?", aliasItemId);
        }
    }

    @Test
    void session_adapter_completes_results_with_all_result_fields_and_interrupts_other_running_sessions() {
        var completedId = UUID.randomUUID();
        var runningId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-08-24T12:00:00Z");
        var endedAt = startedAt.plusSeconds(90);
        var roomId = UUID.randomUUID();
        var winnerId = UUID.randomUUID();
        var sharedSecondAId = UUID.randomUUID();
        var sharedSecondBId = UUID.randomUUID();

        gameSessions.start(new GameSessionPort.StartGameSession(
                completedId, roomId, GameType.LIAR, "{\"category\":\"food\"}", startedAt
        ));
        gameSessions.start(new GameSessionPort.StartGameSession(
                runningId, UUID.randomUUID(), GameType.LIAR, "{\"category\":\"animal\"}", startedAt
        ));
        gameSessions.complete(completedId, List.of(
                new GameSessionPort.GameParticipantResult(winnerId, "우승자", 20, 1, 3),
                new GameSessionPort.GameParticipantResult(sharedSecondAId, "공동2위A", 10, 2, 3),
                new GameSessionPort.GameParticipantResult(sharedSecondBId, "공동2위B", 10, 2, 3)
        ), endedAt);

        assertThat(gameSessions.interruptRunning(endedAt)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from game_sessions where id = ?",
                String.class,
                completedId
        )).isEqualTo("COMPLETED");
        assertThat(sessionEndedAt(completedId)).isEqualTo(endedAt);
        var participants = jdbc.queryForList("""
                select actor_id, nickname, score, rank, rounds_played
                  from game_participants
                 where session_id = ?
                 order by rank, actor_id
                """, completedId);
        assertThat(participants).containsExactlyInAnyOrder(
                java.util.Map.of("actor_id", winnerId, "nickname", "우승자", "score", 20, "rank", 1, "rounds_played", 3),
                java.util.Map.of("actor_id", sharedSecondAId, "nickname", "공동2위A", "score", 10, "rank", 2, "rounds_played", 3),
                java.util.Map.of("actor_id", sharedSecondBId, "nickname", "공동2위B", "score", 10, "rank", 2, "rounds_played", 3)
        );
        assertThat(jdbc.queryForObject(
                "select status from game_sessions where id = ?",
                String.class,
                runningId
        )).isEqualTo("INTERRUPTED");
    }

    @Test
    void completion_loses_to_recovery_without_resurrecting_or_merging_results() {
        var sessionId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-08-24T12:00:00Z");
        var interruptedAt = startedAt.plusSeconds(30);

        gameSessions.start(new GameSessionPort.StartGameSession(
                sessionId, UUID.randomUUID(), GameType.LIAR, "{\"category\":\"food\"}", startedAt
        ));
        assertThat(gameSessions.interruptRunning(interruptedAt)).isEqualTo(1);

        assertThatThrownBy(() -> gameSessions.complete(sessionId, List.of(
                new GameSessionPort.GameParticipantResult(UUID.randomUUID(), "늦은결과", 99, 1, 3)
        ), interruptedAt.plusSeconds(1))).isInstanceOf(GameSessionNotRunningException.class);
        assertThat(jdbc.queryForObject(
                "select status from game_sessions where id = ?",
                String.class,
                sessionId
        )).isEqualTo("INTERRUPTED");
        assertThat(sessionEndedAt(sessionId)).isEqualTo(interruptedAt);
        assertThat(jdbc.queryForObject(
                "select count(*) from game_participants where session_id = ?",
                Integer.class,
                sessionId
        )).isZero();
    }

    @Test
    void session_specific_interrupt_does_not_touch_other_running_sessions() {
        var interruptedId = UUID.randomUUID();
        var unrelatedId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-08-24T12:00:00Z");
        var interruptedAt = startedAt.plusSeconds(15);
        gameSessions.start(new GameSessionPort.StartGameSession(
                interruptedId, UUID.randomUUID(), GameType.LIAR, "{}", startedAt
        ));
        gameSessions.start(new GameSessionPort.StartGameSession(
                unrelatedId, UUID.randomUUID(), GameType.LIAR, "{}", startedAt
        ));

        assertThat(gameSessions.interrupt(interruptedId, interruptedAt)).isTrue();

        assertThat(jdbc.queryForObject("select status from game_sessions where id = ?", String.class, interruptedId))
                .isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("select status from game_sessions where id = ?", String.class, unrelatedId))
                .isEqualTo("RUNNING");
        assertThat(gameSessions.interrupt(interruptedId, interruptedAt.plusSeconds(1))).isFalse();
    }

    @Test
    void concurrent_completions_persist_only_the_single_winning_result_set() throws Exception {
        var sessionId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-08-24T12:00:00Z");
        var firstActorId = UUID.randomUUID();
        var secondActorId = UUID.randomUUID();
        var firstEndedAt = startedAt.plusSeconds(60);
        var secondEndedAt = startedAt.plusSeconds(61);
        var barrier = new CyclicBarrier(2);

        gameSessions.start(new GameSessionPort.StartGameSession(
                sessionId, UUID.randomUUID(), GameType.LIAR, "{\"category\":\"food\"}", startedAt
        ));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> competeToComplete(
                    barrier,
                    sessionId,
                    new GameSessionPort.GameParticipantResult(firstActorId, "첫결과", 10, 1, 3),
                    firstEndedAt
            ));
            var second = executor.submit(() -> competeToComplete(
                    barrier,
                    sessionId,
                    new GameSessionPort.GameParticipantResult(secondActorId, "둘째결과", 20, 1, 3),
                    secondEndedAt
            ));

            var attempts = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            var winner = attempts.stream().filter(CompletionAttempt::completed).findFirst().orElseThrow();
            var loser = attempts.stream().filter(attempt -> !attempt.completed()).findFirst().orElseThrow();

            assertThat(attempts).filteredOn(CompletionAttempt::completed).hasSize(1);
            assertThat(loser.failure()).isInstanceOf(GameSessionNotRunningException.class);
            assertThat(jdbc.queryForObject(
                    "select status from game_sessions where id = ?",
                    String.class,
                    sessionId
            )).isEqualTo("COMPLETED");
            assertThat(sessionEndedAt(sessionId)).isEqualTo(winner.endedAt());
            var participants = jdbc.queryForList("""
                    select actor_id, nickname, score, rank, rounds_played
                      from game_participants
                     where session_id = ?
                    """, sessionId);
            assertThat(participants).containsExactly(java.util.Map.of(
                    "actor_id", winner.actorId(),
                    "nickname", winner.actorId().equals(firstActorId) ? "첫결과" : "둘째결과",
                    "score", winner.actorId().equals(firstActorId) ? 10 : 20,
                    "rank", 1,
                    "rounds_played", 3
            ));
            assertThat(participants).extracting(row -> row.get("actor_id")).doesNotContain(loser.actorId());
        }
    }

    private Instant sessionEndedAt(UUID sessionId) {
        return jdbc.queryForObject(
                "select ended_at from game_sessions where id = ?",
                (resultSet, rowNum) -> resultSet.getObject("ended_at", OffsetDateTime.class).toInstant(),
                sessionId
        );
    }

    private CompletionAttempt competeToComplete(
            CyclicBarrier barrier,
            UUID sessionId,
            GameSessionPort.GameParticipantResult result,
            Instant endedAt
    ) {
        try {
            barrier.await();
            gameSessions.complete(sessionId, List.of(result), endedAt);
            return new CompletionAttempt(result.actorId(), endedAt, true, null);
        } catch (GameSessionNotRunningException exception) {
            return new CompletionAttempt(result.actorId(), endedAt, false, exception);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent completion failed unexpectedly", exception);
        }
    }

    private record CompletionAttempt(UUID actorId, Instant endedAt, boolean completed, Throwable failure) {
    }
}
