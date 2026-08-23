package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.RoomId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatPolicyTest {
    private static final RoomId ROOM_ID = new RoomId(
            UUID.fromString("00000000-0000-0000-0000-000000005001")
    );
    private static final ActorPrincipal ACTOR = ActorPrincipal.guest(new ActorId("chat-actor"), "채팅감자");

    @Test
    void trimsAndRecordsAnAcceptedMessage() {
        var clock = new MutableClock(Instant.parse("2026-08-23T00:00:00Z"));
        var policy = new ChatPolicy(clock);

        var accepted = policy.accept(
                ROOM_ID,
                ACTOR,
                "00000000-0000-0000-0000-000000005011",
                "  다들 준비됐어?  ",
                ignored -> 7L
        ).orElseThrow();

        assertThat(accepted.sequence()).isEqualTo(7L);
        assertThat(accepted.message().actorId()).isEqualTo("chat-actor");
        assertThat(accepted.message().nickname()).isEqualTo("채팅감자");
        assertThat(accepted.message().body()).isEqualTo("다들 준비됐어?");
        assertThat(accepted.message().sentAt()).isEqualTo(clock.instant());
        assertThat(policy.history(ROOM_ID)).containsExactly(accepted.message());
    }

    @Test
    void rejectsUrlControlCharactersAndOutOfRangeBodiesWithoutAllocatingSequence() {
        var policy = new ChatPolicy(Clock.systemUTC());
        var allocations = new AtomicLong();

        assertRejected(policy, "https://example.com", "CHAT_URL_NOT_ALLOWED", allocations);
        assertRejected(policy, "example.com/rooms/123", "CHAT_URL_NOT_ALLOWED", allocations);
        assertRejected(policy, "\ttrimmed away", "CHAT_CONTROL_CHARACTER", allocations);
        assertRejected(policy, "hello\nworld", "CHAT_CONTROL_CHARACTER", allocations);
        assertRejected(policy, "   ", "CHAT_LENGTH_INVALID", allocations);
        assertRejected(policy, "가".repeat(301), "CHAT_LENGTH_INVALID", allocations);
        assertThat(allocations).hasValue(0L);
    }

    @Test
    void limitsEachActorToFiveMessagesPerTenSeconds() {
        var clock = new MutableClock(Instant.parse("2026-08-23T00:00:00Z"));
        var policy = new ChatPolicy(clock);
        var sequence = new AtomicLong();

        for (int index = 0; index < 5; index++) {
            policy.accept(ROOM_ID, ACTOR, requestId(index), "message " + index, ignored -> sequence.incrementAndGet());
        }

        assertThatThrownBy(() -> policy.accept(
                ROOM_ID, ACTOR, requestId(5), "limited", ignored -> sequence.incrementAndGet()
        )).isInstanceOf(ChatPolicy.ChatPolicyViolation.class)
                .extracting("code")
                .isEqualTo("CHAT_RATE_LIMITED");
        assertThat(sequence).hasValue(5L);

        clock.advanceSeconds(10);
        assertThat(policy.accept(
                ROOM_ID, ACTOR, requestId(6), "allowed again", ignored -> sequence.incrementAndGet()
        )).isPresent();
    }

    @Test
    void refillsOneTokenEveryTwoSeconds() {
        var clock = new MutableClock(Instant.parse("2026-08-23T00:00:00Z"));
        var policy = new ChatPolicy(clock);
        var sequence = new AtomicLong();
        for (int index = 0; index < 5; index++) {
            policy.accept(ROOM_ID, ACTOR, requestId(index), "burst " + index, ignored -> sequence.incrementAndGet());
        }

        clock.advanceSeconds(2);

        assertThat(policy.accept(
                ROOM_ID, ACTOR, requestId(20), "refilled", ignored -> sequence.incrementAndGet()
        )).isPresent();
        assertThat(sequence).hasValue(6L);
    }

    @Test
    void duplicateRequestDoesNotAllocateOrRecordAnotherMessage() {
        var policy = new ChatPolicy(Clock.systemUTC());
        var sequence = new AtomicLong();
        var requestId = "00000000-0000-0000-0000-000000005020";

        assertThat(policy.accept(ROOM_ID, ACTOR, requestId, "once", ignored -> sequence.incrementAndGet())).isPresent();
        assertThat(policy.accept(ROOM_ID, ACTOR, requestId, "twice", ignored -> sequence.incrementAndGet())).isEmpty();

        assertThat(sequence).hasValue(1L);
        assertThat(policy.history(ROOM_ID)).extracting(ChatPolicy.ChatMessage::body).containsExactly("once");
    }

    @Test
    void keepsOnlyTheNewestHundredMessagesPerRoom() {
        var policy = new ChatPolicy(Clock.systemUTC());
        var sequence = new AtomicLong();

        for (int index = 0; index < 105; index++) {
            var actor = ActorPrincipal.guest(new ActorId("actor-" + index), "감자" + index);
            policy.accept(ROOM_ID, actor, requestId(index), "message " + index, ignored -> sequence.incrementAndGet());
        }

        assertThat(policy.history(ROOM_ID)).hasSize(100);
        assertThat(policy.history(ROOM_ID).getFirst().body()).isEqualTo("message 5");
        assertThat(policy.history(ROOM_ID).getLast().body()).isEqualTo("message 104");
    }

    private static void assertRejected(
            ChatPolicy policy,
            String body,
            String expectedCode,
            AtomicLong allocations
    ) {
        assertThatThrownBy(() -> policy.accept(
                ROOM_ID,
                ACTOR,
                UUID.randomUUID().toString(),
                body,
                ignored -> allocations.incrementAndGet()
        )).isInstanceOf(ChatPolicy.ChatPolicyViolation.class)
                .extracting("code")
                .isEqualTo(expectedCode);
    }

    private static String requestId(int index) {
        return "00000000-0000-0000-0000-%012d".formatted(index + 1);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
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
