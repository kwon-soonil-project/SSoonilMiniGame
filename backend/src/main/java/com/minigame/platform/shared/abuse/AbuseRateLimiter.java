package com.minigame.platform.shared.abuse;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.room.domain.RoomId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Component
public final class AbuseRateLimiter {
    private static final int MAX_TRACKED_SCOPES = 50_000;

    private final Clock clock;
    private final Limit guestLimit;
    private final Limit passwordLimit;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(128, 0.75f, true);

    @Autowired
    public AbuseRateLimiter(
            Clock clock,
            @Value("${app.abuse.guest.capacity:12}") int guestCapacity,
            @Value("${app.abuse.guest.window:PT1M}") String guestWindow,
            @Value("${app.abuse.password.capacity:5}") int passwordCapacity,
            @Value("${app.abuse.password.window:PT1M}") String passwordWindow
    ) {
        this(
                clock,
                guestCapacity,
                Duration.parse(guestWindow),
                passwordCapacity,
                Duration.parse(passwordWindow)
        );
    }

    public AbuseRateLimiter(
            Clock clock,
            int guestCapacity,
            Duration guestWindow,
            int passwordCapacity,
            Duration passwordWindow
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guestLimit = new Limit(guestCapacity, guestWindow);
        this.passwordLimit = new Limit(passwordCapacity, passwordWindow);
    }

    public synchronized void checkGuest(String clientFingerprint) {
        consume(List.of("guest:" + required(clientFingerprint)), guestLimit);
    }

    public synchronized void checkPassword(ActorId actorId, RoomId roomId, String clientFingerprint) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(roomId, "roomId");
        var room = roomId.value().toString();
        consume(List.of(
                "password:actor:" + actorId.value() + ":" + room,
                "password:network:" + required(clientFingerprint) + ":" + room
        ), passwordLimit);
    }

    public synchronized void passwordSucceeded(ActorId actorId, RoomId roomId, String clientFingerprint) {
        buckets.remove("password:actor:" + actorId.value() + ":" + roomId.value());
        buckets.remove("password:network:" + required(clientFingerprint) + ":" + roomId.value());
    }

    private void consume(List<String> keys, Limit limit) {
        var now = clock.instant();
        var candidates = keys.stream()
                .map(key -> new Candidate(key, refill(buckets.get(key), limit, now)))
                .toList();
        var rejected = candidates.stream().filter(candidate -> candidate.bucket().tokens() < 1.0).findFirst();
        if (rejected.isPresent()) {
            throw new AbuseLimitExceededException(retryAfter(rejected.orElseThrow().bucket(), limit));
        }
        candidates.forEach(candidate -> putBounded(
                candidate.key(),
                new Bucket(candidate.bucket().tokens() - 1.0, now)
        ));
    }

    private static Bucket refill(Bucket current, Limit limit, Instant now) {
        if (current == null) {
            return new Bucket(limit.capacity(), now);
        }
        var elapsedNanos = Math.max(0L, Duration.between(current.refilledAt(), now).toNanos());
        var tokens = Math.min(
                limit.capacity(),
                current.tokens() + elapsedNanos * ((double) limit.capacity() / limit.window().toNanos())
        );
        return new Bucket(tokens, now);
    }

    private static long retryAfter(Bucket bucket, Limit limit) {
        var missing = 1.0 - bucket.tokens();
        var seconds = missing * limit.window().toNanos() / limit.capacity() / 1_000_000_000.0;
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    private void putBounded(String key, Bucket bucket) {
        buckets.put(key, bucket);
        while (buckets.size() > MAX_TRACKED_SCOPES) {
            var eldest = buckets.keySet().iterator().next();
            buckets.remove(eldest);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Client fingerprint is required");
        }
        return value;
    }

    private record Limit(int capacity, Duration window) {
        private Limit {
            if (capacity < 1 || window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("Rate limits require positive capacity and window");
            }
        }
    }

    private record Bucket(double tokens, Instant refilledAt) {
    }

    private record Candidate(String key, Bucket bucket) {
    }
}
