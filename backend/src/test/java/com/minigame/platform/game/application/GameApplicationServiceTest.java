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
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomFixture;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private void advanceToVoting() {
        advanceToDiscussion();
        var discussing = state();
        clock.set(discussing.deadlineAt());
        service.expire(RoomFixture.ROOM_ID, deadline(discussing));
        assertThat(state().phase()).isEqualTo(LiarPhase.VOTING);
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

        @Override
        public boolean available(String categoryCode, Set<UUID> excludedIds, int required) {
            return words.stream().filter(word -> !excludedIds.contains(word.id())).count() >= required;
        }

        @Override
        public List<LiarWord> select(String categoryCode, Set<UUID> excludedIds, int limit) {
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

        private RecordingSessions(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public UUID start(StartGameSession command) {
            operations.add("session:start");
            started.add(command);
            return command.sessionId();
        }

        @Override
        public void complete(UUID sessionId, List<GameParticipantResult> results, Instant endedAt) {
            completed.add(sessionId);
            this.results.add(List.copyOf(results));
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

        private RecordingPublisher(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void publishPublic(EventEnvelope<?> event) {
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

        @Override
        public Cancellation schedule(RoomId roomId, GameDeadline deadline, Runnable callback) {
            var task = new Scheduled(roomId, deadline, callback);
            scheduled.add(task);
            return () -> task.cancelled = true;
        }
    }

    private static final class Scheduled {
        private final RoomId roomId;
        private final GameDeadline deadline;
        private final Runnable callback;
        private boolean cancelled;

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
}
