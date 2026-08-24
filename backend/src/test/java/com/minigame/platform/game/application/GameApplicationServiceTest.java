package com.minigame.platform.game.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.game.domain.GameDeadline;
import com.minigame.platform.game.domain.GameRuleViolation;
import com.minigame.platform.game.domain.liar.LiarGameModule;
import com.minigame.platform.game.domain.liar.LiarGameState;
import com.minigame.platform.game.domain.liar.LiarPhase;
import com.minigame.platform.game.domain.liar.LiarWord;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomFixture;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final ActorPrincipal HOST = ActorPrincipal.guest(RoomFixture.HOST, "방장");
    private static final String START_REQUEST = RoomFixture.requestId("game-start");

    private final InMemoryActiveRoomRepository rooms = new InMemoryActiveRoomRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final List<String> operations = new ArrayList<>();
    private final RecordingSessions sessions = new RecordingSessions(operations);
    private final RecordingPublisher publisher = new RecordingPublisher(operations);
    private final ManualGameScheduler scheduler = new ManualGameScheduler();
    private final StubContent content = new StubContent();
    private GameApplicationService service;

    @BeforeEach
    void setUp() {
        var room = readyRoom();
        rooms.save(room);
        service = new GameApplicationService(
                rooms,
                new GameModuleRegistry(List.of(new LiarGameModule())),
                content,
                sessions,
                scheduler,
                publisher,
                clock,
                new java.util.Random(17)
        );
    }

    @Test
    void start_persists_running_session_before_publishing_secret_roles() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);

        assertThat(operations.getFirst()).isEqualTo("session:start");
        assertThat(operations).containsSubsequence("session:start", "public:GAME_STATE_CHANGED");
        assertThat(publisher.publicEvents).singleElement()
                .extracting(EventEnvelope::type)
                .isEqualTo("GAME_STATE_CHANGED");
        assertThat(publisher.privateEvents).hasSize(4)
                .allSatisfy(delivery -> assertThat(delivery.event().type())
                        .isEqualTo("GAME_PRIVATE_STATE_CHANGED"));
        assertThat(publisher.privateEvents)
                .allSatisfy(delivery -> assertThat(delivery.event().sequence())
                        .isEqualTo(publisher.publicEvents.getFirst().sequence()));
        assertThat(snapshot().status().name()).isEqualTo("PLAYING");
    }

    @Test
    void duplicate_start_request_does_not_create_or_publish_a_second_session() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);

        assertThat(sessions.started).hasSize(1);
        assertThat(publisher.publicEvents).hasSize(1);
        assertThat(scheduler.scheduled).hasSize(1);
    }

    @Test
    void public_payload_never_contains_role_word_or_vote_target() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        advanceToVoting();
        publisher.clear();
        var state = state();
        var voter = state.players().getFirst().actorId();
        var target = state.players().get(1).actorId();

        service.act(principal(voter), RoomFixture.ROOM_ID, RoomFixture.requestId("vote"),
                "VOTE_SUBMIT", Map.of("targetActorId", target.value()));

        var json = publisher.publicEvents.getLast().payload().toString();
        assertThat(json).doesNotContain("liarId", "word", "targetActorId", "role");
        assertThat(publisher.privateEvents).isNotEmpty();
    }

    @Test
    void real_requester_projection_has_private_role_while_public_state_stays_secret() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var room = snapshot();

        for (var participant : room.participants()) {
            var game = service.snapshot(room, participant.actorId()).orElseThrow();
            assertThat((Map<?, ?>) game.publicState()).satisfies(publicState -> {
                assertThat(publicState.containsKey("role")).isFalse();
                assertThat(publicState.containsKey("word")).isFalse();
                assertThat(publicState.containsKey("liarId")).isFalse();
                assertThat(publicState.containsKey("targetActorId")).isFalse();
            });
            assertThat((Map<?, ?>) game.privateState()).satisfies(privateState -> {
                assertThat(privateState.containsKey("role")).isTrue();
                assertThat(privateState.containsKey("category")).isTrue();
                assertThat(privateState.containsKey("liarId")).isFalse();
            });
        }
    }

    @Test
    void stale_deadline_is_ignored_after_replacement_and_old_schedule_is_cancelled() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var first = scheduler.scheduled.getFirst();
        clock.set(first.deadline().at());
        service.expire(RoomFixture.ROOM_ID, first.deadline());
        var eventsAfterAdvance = publisher.publicEvents.size();

        first.callback().run();

        assertThat(first.cancelled).isTrue();
        assertThat(publisher.publicEvents).hasSize(eventsAfterAdvance);
        assertThat(state().phase()).isEqualTo(LiarPhase.HINTING);
    }

    @Test
    void discussion_proposal_uses_the_current_room_host_after_transfer() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        advanceToDiscussion();
        rooms.withRoom(RoomFixture.ROOM_ID, room -> room.transferHost(
                RoomFixture.HOST,
                RoomFixture.GUEST_1,
                RoomFixture.requestId("host-transfer-in-game")
        ));

        assertThatThrownBy(() -> service.act(
                HOST,
                RoomFixture.ROOM_ID,
                RoomFixture.requestId("old-host-proposal"),
                "DISCUSSION_END_PROPOSE",
                Map.of()
        )).isInstanceOfSatisfying(GameRuleViolation.class,
                error -> assertThat(error.code()).isEqualTo("GAME_HOST_REQUIRED"));

        service.act(
                principal(RoomFixture.GUEST_1),
                RoomFixture.ROOM_ID,
                RoomFixture.requestId("new-host-proposal"),
                "DISCUSSION_END_PROPOSE",
                Map.of()
        );
        assertThat(state().discussionEndRespondents()).containsExactly(RoomFixture.GUEST_1);
    }

    @Test
    void round_result_promotes_waiting_spectator_before_synchronizing_the_next_round() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var spectator = new ActorId("spectator-next");
        rooms.withRoom(RoomFixture.ROOM_ID, room -> room.join(
                spectator,
                "다음참가자",
                false,
                RoomFixture.requestId("join-during-game")
        ));
        var current = state();
        var departing = current.players().stream()
                .map(player -> player.actorId())
                .filter(actorId -> !actorId.equals(current.liarId()))
                .findFirst()
                .orElseThrow();
        rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.participantLeft(room, departing, clock.instant());
            room.leave(departing, RoomFixture.requestId("leave-during-game"));
            return null;
        });
        var roundResult = state();
        assertThat(roundResult.phase()).isEqualTo(LiarPhase.ROUND_RESULT);
        publisher.clear();
        clock.set(roundResult.deadlineAt());

        service.expire(RoomFixture.ROOM_ID, deadline(roundResult));

        assertThat(snapshot().participants())
                .filteredOn(participant -> participant.actorId().equals(spectator))
                .allMatch(participant -> !participant.spectator());
        assertThat(state().round()).isEqualTo(2);
        assertThat(state().players()).extracting(player -> player.actorId()).contains(spectator);
        assertThat(publisher.publicEvents).extracting(EventEnvelope::type)
                .startsWith("PLAYER_SPECTATOR_CHANGED", "GAME_STATE_CHANGED");
        assertThat(publisher.publicEvents.get(1).sequence())
                .isEqualTo(publisher.publicEvents.getFirst().sequence() + 1);
    }

    @Test
    void roster_below_four_hands_off_to_persisted_game_result_then_returns_to_waiting() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var current = state();
        var departing = current.players().stream()
                .map(player -> player.actorId())
                .filter(actorId -> !actorId.equals(current.liarId()))
                .filter(actorId -> !actorId.equals(RoomFixture.HOST))
                .findFirst()
                .orElseThrow();
        rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.participantLeft(room, departing, clock.instant());
            room.leave(departing, RoomFixture.requestId("below-four-leave"));
            return null;
        });
        var roundResult = state();
        clock.set(roundResult.deadlineAt());

        service.expire(RoomFixture.ROOM_ID, deadline(roundResult));

        assertThat(state().phase()).isEqualTo(LiarPhase.GAME_RESULT);
        assertThat(sessions.completed).hasSize(1);
        assertThat(sessions.results).singleElement().satisfies(results -> {
            assertThat(results).hasSize(4);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.rank()).isPositive();
                assertThat(result.roundsPlayed()).isEqualTo(1);
            });
        });

        service.act(HOST, RoomFixture.ROOM_ID, RoomFixture.requestId("return-to-waiting"),
                "RETURN_TO_WAITING", Map.of());

        assertThat(snapshot().status().name()).isEqualTo("WAITING");
        assertThat(snapshot().gameRuntime()).isEmpty();
        assertThat(snapshot().recentContentIds()).hasSize(3);
        assertThat(publisher.publicEvents.getLast().payload().toString()).contains("game=null");
    }

    @Test
    void normalized_discussion_proposal_requires_current_host_but_accepts_host_whitespace() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        advanceToDiscussion();

        assertThatThrownBy(() -> service.act(
                principal(RoomFixture.GUEST_1), RoomFixture.ROOM_ID,
                RoomFixture.requestId("wrapped-non-host-proposal"),
                "  DISCUSSION_END_PROPOSE  ", Map.of()
        )).isInstanceOfSatisfying(GameRuleViolation.class,
                error -> assertThat(error.code()).isEqualTo("GAME_HOST_REQUIRED"));

        service.act(HOST, RoomFixture.ROOM_ID,
                RoomFixture.requestId("wrapped-host-proposal"),
                "  DISCUSSION_END_PROPOSE  ", Map.of());
        assertThat(state().discussionEndRespondents()).containsExactly(RoomFixture.HOST);
    }

    @Test
    void unauthorized_and_duplicate_start_do_not_query_content() {
        assertThatThrownBy(() -> service.start(
                principal(RoomFixture.GUEST_1), RoomFixture.ROOM_ID,
                RoomFixture.requestId("unauthorized-start")
        )).isInstanceOf(com.minigame.platform.room.domain.RoomRuleViolation.class);
        assertThat(content.selectCalls).isZero();

        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        content.selectCalls = 0;
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);

        assertThat(content.selectCalls).isZero();
    }

    @Test
    void start_rejects_when_settings_change_after_content_selection() {
        content.duringSelect = () -> rooms.withRoom(RoomFixture.ROOM_ID, room -> room.updateSettings(
                RoomFixture.HOST,
                new com.minigame.platform.room.domain.RoomSettings(
                        com.minigame.platform.room.domain.GameType.LIAR, 10, 2, 30, 90, "changed-pack"
                ),
                RoomFixture.requestId("settings-race")
        ));

        assertThatThrownBy(() -> service.start(HOST, RoomFixture.ROOM_ID,
                RoomFixture.requestId("start-settings-race")))
                .isInstanceOfSatisfying(GameRuleViolation.class,
                        error -> assertThat(error.code()).isEqualTo("GAME_START_STATE_CHANGED"));

        assertThat(snapshot().gameRuntime()).isEmpty();
        assertThat(sessions.started).isEmpty();
    }

    @Test
    void start_scheduler_failure_creates_no_session_and_same_request_can_retry() {
        scheduler.failNext = true;

        assertThatThrownBy(() -> service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST))
                .isInstanceOf(IllegalStateException.class).hasMessage("schedule failed");
        assertThat(snapshot().gameRuntime()).isEmpty();
        assertThat(sessions.started).isEmpty();
        assertThat(sessions.interrupted).isEmpty();

        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        assertThat(snapshot().gameRuntime()).isPresent();
        assertThat(sessions.started).hasSize(1);
    }

    @Test
    void start_session_failure_leaves_room_unstarted_and_same_request_can_retry() {
        sessions.failStart = true;
        sessions.failInterrupt = true;

        assertThatThrownBy(() -> service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST))
                .isInstanceOf(IllegalStateException.class).hasMessage("start failed");
        assertThat(snapshot().gameRuntime()).isEmpty();
        assertThat(scheduler.active()).isNull();
        assertThat(scheduler.scheduled).singleElement().matches(task -> task.cancelled);
        assertThat(sessions.interrupted).isEmpty();

        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        assertThat(snapshot().gameRuntime()).isPresent();
        assertThat(sessions.started).hasSize(1);
    }

    @Test
    void action_scheduler_failure_keeps_old_state_and_deadline_and_allows_same_request_retry() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var reveal = state();
        clock.set(reveal.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(reveal));
        var before = state();
        var oldSchedule = scheduler.active();
        var requestId = RoomFixture.requestId("retry-after-schedule-failure");
        scheduler.failNext = true;

        assertThatThrownBy(() -> service.act(
                principal(before.currentHinter()), RoomFixture.ROOM_ID, requestId,
                "HINT_SUBMIT", Map.of("hint", "달콤한 향기")
        )).isInstanceOf(IllegalStateException.class).hasMessage("schedule failed");

        assertThat(state()).isEqualTo(before);
        assertThat(oldSchedule.cancelled).isFalse();
        service.act(principal(before.currentHinter()), RoomFixture.ROOM_ID, requestId,
                "HINT_SUBMIT", Map.of("hint", "달콤한 향기"));
        assertThat(state().hints()).containsKey(before.currentHinter());
    }

    @Test
    void publisher_failure_is_best_effort_and_keeps_an_active_deadline() {
        publisher.failPublic = true;

        assertThatCode(() -> service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST))
                .doesNotThrowAnyException();

        assertThat(snapshot().gameRuntime()).isPresent();
        assertThat(scheduler.active()).isNotNull();
        assertThat(scheduler.active().cancelled).isFalse();
    }

    @Test
    void action_publisher_failure_keeps_the_committed_state_deadline_and_idempotency() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var reveal = state();
        clock.set(reveal.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(reveal));
        var hinting = state();
        var requestId = RoomFixture.requestId("action-publisher-failure");
        publisher.failPublic = true;

        assertThatCode(() -> service.act(
                principal(hinting.currentHinter()), RoomFixture.ROOM_ID, requestId,
                "HINT_SUBMIT", Map.of("hint", "발행 실패 후에도 보존")
        )).doesNotThrowAnyException();

        var committed = state();
        assertThat(committed.hints()).containsKey(hinting.currentHinter());
        assertThat(scheduler.active()).isNotNull();
        assertThat(scheduler.active().cancelled).isFalse();
        service.act(principal(hinting.currentHinter()), RoomFixture.ROOM_ID, requestId,
                "HINT_SUBMIT", Map.of("hint", "중복은 무시"));
        assertThat(state()).isEqualTo(committed);
    }

    @Test
    void promotion_scheduler_failure_keeps_spectator_and_round_result_until_retry() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var spectator = new ActorId("promotion-retry-spectator");
        rooms.withRoom(RoomFixture.ROOM_ID, room -> room.join(
                spectator, "승격재시도", false, RoomFixture.requestId("promotion-retry-join")
        ));
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        var oldSchedule = scheduler.active();
        clock.set(roundResult.deadlineAt());
        scheduler.failNext = true;

        oldSchedule.fire();
        assertThat(oldSchedule.failures).isEqualTo(1);
        assertThat(state()).isEqualTo(roundResult);
        assertThat(oldSchedule.cancelled).isFalse();
        assertThat(snapshot().participants())
                .filteredOn(participant -> participant.actorId().equals(spectator))
                .allMatch(participant -> participant.spectator());

        oldSchedule.fire();
        assertThat(state().round()).isEqualTo(2);
        assertThat(state().players()).extracting(player -> player.actorId()).contains(spectator);
        assertThat(oldSchedule.cancelled).isTrue();
    }

    @Test
    void game_result_scheduler_failure_does_not_complete_session_until_retry() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        var oldSchedule = scheduler.active();
        clock.set(roundResult.deadlineAt());
        scheduler.failNext = true;

        oldSchedule.fire();
        assertThat(oldSchedule.failures).isEqualTo(1);
        assertThat(state()).isEqualTo(roundResult);
        assertThat(sessions.completed).isEmpty();
        assertThat(oldSchedule.cancelled).isFalse();

        oldSchedule.fire();
        assertThat(state().phase()).isEqualTo(LiarPhase.GAME_RESULT);
        assertThat(sessions.completed).hasSize(1);
        assertThat(oldSchedule.cancelled).isTrue();
    }

    @Test
    void game_result_completion_failure_preserves_round_result_and_retryability() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        var oldSchedule = scheduler.active();
        clock.set(roundResult.deadlineAt());
        sessions.failComplete = true;

        oldSchedule.fire();
        assertThat(oldSchedule.failures).isEqualTo(1);
        assertThat(state()).isEqualTo(roundResult);
        assertThat(oldSchedule.cancelled).isFalse();

        oldSchedule.fire();
        assertThat(state().phase()).isEqualTo(LiarPhase.GAME_RESULT);
        assertThat(sessions.completed).hasSize(1);
        assertThat(oldSchedule.cancelled).isTrue();
    }

    @Test
    void duplicate_return_after_runtime_removal_is_a_no_op() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        clock.set(roundResult.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(roundResult));
        var requestId = RoomFixture.requestId("idempotent-return");

        service.act(HOST, RoomFixture.ROOM_ID, requestId, "RETURN_TO_WAITING", Map.of());
        var sequence = snapshot().sequence();
        service.act(HOST, RoomFixture.ROOM_ID, requestId, "RETURN_TO_WAITING", Map.of());

        assertThat(snapshot().sequence()).isEqualTo(sequence);
        assertThat(snapshot().gameRuntime()).isEmpty();
    }

    @Test
    void final_return_activates_joined_spectator_for_the_next_game() {
        rooms.withRoom(RoomFixture.ROOM_ID, room -> room.updateSettings(
                RoomFixture.HOST,
                new com.minigame.platform.room.domain.RoomSettings(
                        com.minigame.platform.room.domain.GameType.LIAR, 10, 1, 30, 90, "all"
                ),
                RoomFixture.requestId("single-round-settings")
        ));
        rooms.withRoom(RoomFixture.ROOM_ID, room -> {
            var events = new ArrayList<com.minigame.platform.room.domain.RoomEvent>();
            for (var actorId : List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3)) {
                events.addAll(room.changeReady(
                        actorId, true, RoomFixture.requestId("single-round-ready-" + actorId.value())
                ));
            }
            return events;
        });
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var spectator = new ActorId("final-round-spectator");
        rooms.withRoom(RoomFixture.ROOM_ID, room -> room.join(
                spectator, "다음게임", false, RoomFixture.requestId("final-spectator-join")
        ));
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        clock.set(roundResult.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(roundResult));
        publisher.clear();

        service.act(HOST, RoomFixture.ROOM_ID, RoomFixture.requestId("return-with-spectator"),
                "RETURN_TO_WAITING", Map.of());

        assertThat(snapshot().participants())
                .filteredOn(participant -> participant.actorId().equals(spectator))
                .allMatch(participant -> !participant.spectator() && !participant.ready());
        assertThat(publisher.publicEvents).extracting(EventEnvelope::type)
                .containsSubsequence("PLAYER_SPECTATOR_CHANGED", "GAME_STATE_CHANGED");
    }

    @Test
    void content_selection_falls_back_without_recent_exclusions_for_the_next_game() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        moveToRoundResultWithThreePlayers();
        var roundResult = state();
        clock.set(roundResult.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(roundResult));
        service.act(HOST, RoomFixture.ROOM_ID, RoomFixture.requestId("fallback-return"),
                "RETURN_TO_WAITING", Map.of());
        var missing = List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3).stream()
                .filter(actorId -> snapshot().participants().stream()
                        .noneMatch(participant -> participant.actorId().equals(actorId)))
                .findFirst().orElseThrow();
        rooms.withRoom(RoomFixture.ROOM_ID, room -> {
            var events = new ArrayList<com.minigame.platform.room.domain.RoomEvent>();
            events.addAll(room.join(
                    missing, "재입장", false, RoomFixture.requestId("fallback-rejoin")
            ));
            for (var participant : room.snapshot().participants()) {
                if (!participant.actorId().equals(room.snapshot().hostId()) && !participant.spectator()) {
                    events.addAll(room.changeReady(
                            participant.actorId(), true,
                            RoomFixture.requestId("fallback-ready-" + participant.actorId().value())
                    ));
                }
            }
            return events;
        });
        content.selectCalls = 0;

        service.start(HOST, RoomFixture.ROOM_ID, RoomFixture.requestId("fallback-second-start"));

        assertThat(content.selectCalls).isEqualTo(2);
        assertThat(sessions.started).hasSize(2);
    }

    @Test
    void closing_running_room_interrupts_its_specific_session_and_cancels_deadline() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var sessionId = state().sessionId();
        var activeSchedule = scheduler.active();

        rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.roomClosed(room, clock.instant());
            return null;
        });

        assertThat(sessions.interrupted).containsExactly(sessionId);
        assertThat(activeSchedule.cancelled).isTrue();
    }

    @Test
    void room_close_does_not_report_success_until_deadline_cancellation_can_be_retried() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var activeSchedule = scheduler.active();
        scheduler.failCancelNext = true;

        assertThatThrownBy(() -> rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.roomClosed(room, clock.instant());
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessage("cancel failed");
        assertThat(activeSchedule.cancelled).isFalse();

        rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.roomClosed(room, clock.instant());
            return null;
        });
        assertThat(activeSchedule.cancelled).isTrue();
    }

    @Test
    void real_close_removes_room_despite_room_and_lobby_publication_failures() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var gameSessionId = state().sessionId();
        var unrelatedSessionId = UUID.randomUUID();
        sessions.start(new GameSessionPort.StartGameSession(
                unrelatedSessionId, UUID.randomUUID(),
                com.minigame.platform.room.domain.GameType.LIAR, "{}", clock.instant()
        ));
        var roomService = roomService();
        leaveGuests(roomService);
        var activeSchedule = scheduler.active();
        publisher.failPublic = true;
        publisher.failLobby = true;

        assertThatCode(() -> roomService.leave(
                HOST, RoomFixture.ROOM_ID, RoomFixture.requestId("real-close-publisher-failure")
        )).doesNotThrowAnyException();

        assertThat(rooms.findById(RoomFixture.ROOM_ID)).isEmpty();
        assertThat(sessions.interrupted).containsExactly(gameSessionId);
        assertThat(sessions.running).contains(unrelatedSessionId).doesNotContain(gameSessionId);
        assertThat(activeSchedule.cancelled).isTrue();
        assertThat(publisher.failPublic).isFalse();
        assertThat(publisher.failLobby).isFalse();
    }

    @Test
    void real_close_interrupt_failure_leaves_request_unconsumed_for_clean_retry() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var gameSessionId = state().sessionId();
        var roomService = roomService();
        leaveGuests(roomService);
        var activeSchedule = scheduler.active();
        var requestId = RoomFixture.requestId("real-close-interrupt-retry");
        sessions.failInterrupt = true;

        assertThatThrownBy(() -> roomService.leave(HOST, RoomFixture.ROOM_ID, requestId))
                .isInstanceOf(IllegalStateException.class).hasMessage("interrupt failed");
        assertThat(rooms.findById(RoomFixture.ROOM_ID)).isPresent();
        assertThat(sessions.running).contains(gameSessionId);
        assertThat(activeSchedule.cancelled).isFalse();

        roomService.leave(HOST, RoomFixture.ROOM_ID, requestId);
        assertThat(rooms.findById(RoomFixture.ROOM_ID)).isEmpty();
        assertThat(sessions.interrupted).containsExactly(gameSessionId);
        assertThat(activeSchedule.cancelled).isTrue();
    }

    @Test
    void real_close_cancel_failure_leaves_request_unconsumed_for_clean_retry() {
        service.start(HOST, RoomFixture.ROOM_ID, START_REQUEST);
        var gameSessionId = state().sessionId();
        var roomService = roomService();
        leaveGuests(roomService);
        var activeSchedule = scheduler.active();
        var requestId = RoomFixture.requestId("real-close-cancel-retry");
        scheduler.failCancelNext = true;

        assertThatThrownBy(() -> roomService.leave(HOST, RoomFixture.ROOM_ID, requestId))
                .isInstanceOf(IllegalStateException.class).hasMessage("cancel failed");
        assertThat(rooms.findById(RoomFixture.ROOM_ID)).isPresent();
        assertThat(activeSchedule.cancelled).isFalse();

        roomService.leave(HOST, RoomFixture.ROOM_ID, requestId);
        assertThat(rooms.findById(RoomFixture.ROOM_ID)).isEmpty();
        assertThat(sessions.interrupted).containsExactly(gameSessionId);
        assertThat(activeSchedule.cancelled).isTrue();
    }

    private void advanceToVoting() {
        advanceToDiscussion();
        var discussing = state();
        clock.set(discussing.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(discussing));
        assertThat(state().phase()).isEqualTo(LiarPhase.VOTING);
    }

    private RoomApplicationService roomService() {
        return new RoomApplicationService(
                rooms,
                new PlainPasswordEncoder(),
                publisher,
                clock,
                new ChatPolicy(clock),
                new com.minigame.platform.shared.abuse.AbuseRateLimiter(
                        clock, Integer.MAX_VALUE, Duration.ofMinutes(1),
                        Integer.MAX_VALUE, Duration.ofMinutes(1)
                ),
                service
        );
    }

    private void leaveGuests(RoomApplicationService roomService) {
        for (var guest : List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3)) {
            roomService.leave(
                    principal(guest), RoomFixture.ROOM_ID,
                    RoomFixture.requestId("real-close-leave-" + guest.value())
            );
        }
        assertThat(snapshot().participants()).singleElement()
                .extracting(participant -> participant.actorId()).isEqualTo(RoomFixture.HOST);
    }

    private void moveToRoundResultWithThreePlayers() {
        var current = state();
        var departing = current.players().stream()
                .map(player -> player.actorId())
                .filter(actorId -> !actorId.equals(current.liarId()))
                .filter(actorId -> !actorId.equals(RoomFixture.HOST))
                .findFirst().orElseThrow();
        rooms.withRoomValue(RoomFixture.ROOM_ID, room -> {
            service.participantLeft(room, departing, clock.instant());
            room.leave(departing, RoomFixture.requestId("move-to-round-result-" + departing.value()));
            return null;
        });
        assertThat(state().phase()).isEqualTo(LiarPhase.ROUND_RESULT);
    }

    private void advanceToDiscussion() {
        var reveal = state();
        clock.set(reveal.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(reveal));
        while (state().phase() == LiarPhase.HINTING) {
            var hinting = state();
            service.act(
                    principal(hinting.currentHinter()),
                    RoomFixture.ROOM_ID,
                    UUID.randomUUID().toString(),
                    "HINT_SUBMIT",
                    Map.of("hint", "서로 다른 힌트 " + hinting.hintIndex())
            );
        }
        assertThat(state().phase()).isEqualTo(LiarPhase.DISCUSSING);
    }

    private Room readyRoom() {
        var room = RoomFixture.roomWithFourParticipants();
        for (var actorId : List.of(RoomFixture.GUEST_1, RoomFixture.GUEST_2, RoomFixture.GUEST_3)) {
            room.changeReady(actorId, true, RoomFixture.requestId("ready-game-" + actorId.value()));
        }
        return room;
    }

    private Room.Snapshot snapshot() {
        return rooms.findById(RoomFixture.ROOM_ID).orElseThrow();
    }

    private LiarGameState state() {
        return (LiarGameState) snapshot().gameRuntime().orElseThrow().state();
    }

    private static GameDeadline deadline(LiarGameState state) {
        return new GameDeadline(state.sessionId(), state.round(), state.phaseVersion(), state.deadlineAt());
    }

    private static ActorPrincipal principal(ActorId actorId) {
        return ActorPrincipal.guest(actorId, "참가자");
    }

    private static final class StubContent implements LiarContentPort {
        private final List<LiarWord> words = List.of(
                word("00000000-0000-0000-0000-000000006001", "사과"),
                word("00000000-0000-0000-0000-000000006002", "호랑이"),
                word("00000000-0000-0000-0000-000000006003", "버스")
        );
        private int selectCalls;
        private Runnable duringSelect;

        @Override
        public boolean available(String categoryCode, Set<UUID> excludedIds, int required) {
            return words.stream().filter(word -> !excludedIds.contains(word.id())).count() >= required;
        }

        @Override
        public List<LiarWord> select(String categoryCode, Set<UUID> excludedIds, int limit) {
            selectCalls++;
            if (duringSelect != null) {
                var callback = duringSelect;
                duringSelect = null;
                callback.run();
            }
            return words.stream().filter(word -> !excludedIds.contains(word.id())).limit(limit).toList();
        }

        private static LiarWord word(String id, String answer) {
            return new LiarWord(UUID.fromString(id), "all", answer, Set.of());
        }
    }

    private static final class RecordingSessions implements GameSessionPort {
        private final List<String> operations;
        private final List<StartGameSession> started = new ArrayList<>();
        private final List<UUID> completed = new ArrayList<>();
        private final List<List<GameParticipantResult>> results = new ArrayList<>();
        private final List<UUID> interrupted = new ArrayList<>();
        private final Set<UUID> running = new HashSet<>();
        private boolean failStart;
        private boolean failComplete;
        private boolean failInterrupt;

        private RecordingSessions(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public UUID start(StartGameSession command) {
            operations.add("session:start");
            if (failStart) {
                failStart = false;
                throw new IllegalStateException("start failed");
            }
            started.add(command);
            running.add(command.sessionId());
            return command.sessionId();
        }

        @Override
        public void complete(UUID sessionId, List<GameParticipantResult> results, Instant endedAt) {
            if (failComplete) {
                failComplete = false;
                throw new IllegalStateException("complete failed");
            }
            completed.add(sessionId);
            running.remove(sessionId);
            this.results.add(List.copyOf(results));
        }

        @Override
        public boolean interrupt(UUID sessionId, Instant interruptedAt) {
            if (failInterrupt) {
                failInterrupt = false;
                throw new IllegalStateException("interrupt failed");
            }
            if (!running.remove(sessionId)) {
                return false;
            }
            interrupted.add(sessionId);
            return true;
        }

        @Override
        public int interruptRunning(Instant interruptedAt) {
            return 0;
        }
    }

    private static final class RecordingPublisher implements RoomEventPublisher {
        private final List<String> operations;
        private final List<EventEnvelope<?>> publicEvents = new ArrayList<>();
        private final List<PrivateDelivery> privateEvents = new ArrayList<>();
        private final List<EventEnvelope<?>> lobbyEvents = new ArrayList<>();
        private boolean failPublic;
        private boolean failLobby;

        private RecordingPublisher(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void publishPublic(EventEnvelope<?> event) {
            if (failPublic) {
                failPublic = false;
                throw new IllegalStateException("publisher failed");
            }
            operations.add("public:" + event.type());
            publicEvents.add(event);
        }

        @Override
        public void publishPrivate(String userName, EventEnvelope<?> event) {
            operations.add("private:" + event.type());
            privateEvents.add(new PrivateDelivery(userName, event));
        }

        @Override
        public void publishLobby(EventEnvelope<?> event) {
            if (failLobby) {
                failLobby = false;
                throw new IllegalStateException("lobby publisher failed");
            }
            lobbyEvents.add(event);
        }

        void clear() {
            publicEvents.clear();
            privateEvents.clear();
            lobbyEvents.clear();
        }
    }

    private record PrivateDelivery(String userName, EventEnvelope<?> event) {
    }

    private static final class ManualGameScheduler implements GameSchedulePort {
        private final List<Scheduled> scheduled = new ArrayList<>();
        private boolean failNext;
        private boolean failCancelNext;

        @Override
        public Cancellation schedule(RoomId roomId, GameDeadline deadline, Runnable callback) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("schedule failed");
            }
            var task = new Scheduled(roomId, deadline, callback);
            scheduled.add(task);
            return () -> {
                if (failCancelNext) {
                    failCancelNext = false;
                    throw new IllegalStateException("cancel failed");
                }
                task.cancelled = true;
            };
        }

        Scheduled active() {
            return scheduled.stream().filter(task -> !task.cancelled).reduce((first, second) -> second).orElse(null);
        }
    }

    private static final class Scheduled {
        private final RoomId roomId;
        private final GameDeadline deadline;
        private final Runnable callback;
        private boolean cancelled;
        private int failures;

        private Scheduled(RoomId roomId, GameDeadline deadline, Runnable callback) {
            this.roomId = roomId;
            this.deadline = deadline;
            this.callback = callback;
        }

        GameDeadline deadline() {
            return deadline;
        }

        Runnable callback() {
            return callback;
        }

        void fire() {
            if (cancelled) {
                return;
            }
            try {
                callback.run();
            } catch (RuntimeException exception) {
                failures++;
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class PlainPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return rawPassword.toString();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return rawPassword.toString().equals(encodedPassword);
        }
    }
}
