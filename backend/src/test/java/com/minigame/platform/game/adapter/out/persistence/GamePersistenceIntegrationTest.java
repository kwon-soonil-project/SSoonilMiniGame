package com.minigame.platform.game.adapter.out.persistence;

import com.minigame.platform.game.application.GameSessionPort;
import com.minigame.platform.game.application.LiarContentPort;
import com.minigame.platform.room.domain.GameType;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
    void session_adapter_completes_results_and_interrupts_other_running_sessions() {
        var completedId = UUID.randomUUID();
        var runningId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-08-24T12:00:00Z");
        var endedAt = startedAt.plusSeconds(90);
        var roomId = UUID.randomUUID();

        gameSessions.start(new GameSessionPort.StartGameSession(
                completedId, roomId, GameType.LIAR, "{\"category\":\"food\"}", startedAt
        ));
        gameSessions.start(new GameSessionPort.StartGameSession(
                runningId, UUID.randomUUID(), GameType.LIAR, "{\"category\":\"animal\"}", startedAt
        ));
        gameSessions.complete(completedId, List.of(
                new GameSessionPort.GameParticipantResult(UUID.randomUUID(), "우승자", 20, 1, 3),
                new GameSessionPort.GameParticipantResult(UUID.randomUUID(), "공동2위", 10, 2, 3)
        ), endedAt);

        assertThat(gameSessions.interruptRunning(endedAt)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from game_sessions where id = ?",
                String.class,
                completedId
        )).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select count(*) from game_participants where session_id = ?",
                Integer.class,
                completedId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select status from game_sessions where id = ?",
                String.class,
                runningId
        )).isEqualTo("INTERRUPTED");
    }
}
