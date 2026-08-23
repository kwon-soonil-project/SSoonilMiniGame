package com.minigame.platform.shared.abuse;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.room.domain.RoomId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbuseRateLimiterTest {
    private static final RoomId ROOM = new RoomId(UUID.fromString("00000000-0000-0000-0000-000000009101"));
    private static final ActorId ACTOR = new ActorId("rate-actor");

    @Test
    void guestLimitRefillsAfterItsConfiguredWindow() {
        var clock = new MutableClock(Instant.parse("2026-08-23T00:00:00Z"));
        var limiter = new AbuseRateLimiter(clock, 2, Duration.ofSeconds(10), 2, Duration.ofSeconds(10));

        limiter.checkGuest("ip-hash-a");
        limiter.checkGuest("ip-hash-a");
        assertThatThrownBy(() -> limiter.checkGuest("ip-hash-a"))
                .isInstanceOf(AbuseLimitExceededException.class)
                .extracting("retryAfterSeconds").isEqualTo(5L);

        clock.advanceSeconds(5);
        assertThatCode(() -> limiter.checkGuest("ip-hash-a")).doesNotThrowAnyException();
    }

    @Test
    void passwordAttemptsAreScopedByActorAndAlsoByClientNetwork() {
        var limiter = new AbuseRateLimiter(Clock.systemUTC(), 10, Duration.ofMinutes(1), 2, Duration.ofMinutes(1));
        limiter.checkPassword(ACTOR, ROOM, "ip-hash-a");
        limiter.checkPassword(ACTOR, ROOM, "ip-hash-a");

        assertThatThrownBy(() -> limiter.checkPassword(ACTOR, ROOM, "ip-hash-b"))
                .isInstanceOf(AbuseLimitExceededException.class);
        assertThatThrownBy(() -> limiter.checkPassword(new ActorId("new-actor"), ROOM, "ip-hash-a"))
                .isInstanceOf(AbuseLimitExceededException.class);
        assertThatCode(() -> limiter.checkPassword(new ActorId("new-actor"), ROOM, "ip-hash-b"))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulPasswordVerificationClearsActorAndNetworkBudgetsForLegitimateFollowups() {
        var limiter = new AbuseRateLimiter(Clock.systemUTC(), 10, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));
        limiter.checkPassword(ACTOR, ROOM, "ip-hash-a");
        limiter.passwordSucceeded(ACTOR, ROOM, "ip-hash-a");

        assertThatCode(() -> {
            limiter.checkPassword(new ActorId("new-actor"), ROOM, "ip-hash-a");
            limiter.checkPassword(ACTOR, ROOM, "ip-hash-b");
        }).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
