package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.RoomId;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

@Component
public final class ChatPolicy {
    private static final int MAX_BODY_CODE_POINTS = 300;
    private static final int MAX_MESSAGES_PER_WINDOW = 5;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(10);
    private static final double TOKENS_PER_SECOND =
            (double) MAX_MESSAGES_PER_WINDOW / RATE_WINDOW.toSeconds();
    private static final int HISTORY_LIMIT = 100;
    private static final int REMEMBERED_REQUEST_LIMIT = 4_096;
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|www\\.|\\b[a-z0-9](?:[a-z0-9-]{0,62}\\.)+[a-z]{2,24}(?:[/?:#]\\S*)?)"
    );

    private final Clock clock;
    private final Map<RoomId, ArrayDeque<ChatMessage>> histories = new HashMap<>();
    private final Map<RateKey, TokenBucket> rates = new HashMap<>();
    private final Set<RequestKey> processedRequests = new HashSet<>();
    private final ArrayDeque<RequestKey> processedRequestOrder = new ArrayDeque<>();

    public ChatPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Optional<AcceptedChat> accept(
            RoomId roomId,
            ActorPrincipal actor,
            String requestId,
            String rawBody,
            ToLongFunction<ChatMessage> sequenceAllocator
    ) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
        var request = new RequestKey(roomId, actor.actorId(), canonicalRequestId(requestId));
        if (processedRequests.contains(request)) {
            return Optional.empty();
        }

        var body = normalizeBody(rawBody);
        var now = clock.instant();
        var rateKey = new RateKey(roomId, actor.actorId());
        var bucket = rates.getOrDefault(
                rateKey,
                new TokenBucket(MAX_MESSAGES_PER_WINDOW, now)
        );
        var elapsedNanos = Math.max(0L, Duration.between(bucket.refilledAt(), now).toNanos());
        var available = Math.min(
                MAX_MESSAGES_PER_WINDOW,
                bucket.tokens() + elapsedNanos / 1_000_000_000.0 * TOKENS_PER_SECOND
        );
        if (available < 1.0) {
            throw new ChatPolicyViolation("CHAT_RATE_LIMITED");
        }

        var message = new ChatMessage(
                UUID.randomUUID(),
                actor.actorId().value(),
                actor.nickname(),
                body,
                now
        );
        long sequence = sequenceAllocator.applyAsLong(message);
        rates.put(rateKey, new TokenBucket(available - 1.0, now));
        remember(request);
        var history = histories.computeIfAbsent(roomId, ignored -> new ArrayDeque<>());
        history.addLast(message);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
        return Optional.of(new AcceptedChat(sequence, message));
    }

    public synchronized List<ChatMessage> history(RoomId roomId) {
        return List.copyOf(histories.getOrDefault(roomId, new ArrayDeque<>()));
    }

    public synchronized void clear(RoomId roomId) {
        histories.remove(roomId);
        rates.keySet().removeIf(key -> key.roomId().equals(roomId));
        var retained = new ArrayList<RequestKey>();
        for (var request : processedRequestOrder) {
            if (!request.roomId().equals(roomId)) {
                retained.add(request);
            } else {
                processedRequests.remove(request);
            }
        }
        processedRequestOrder.clear();
        processedRequestOrder.addAll(retained);
    }

    private static String normalizeBody(String rawBody) {
        if (rawBody == null) {
            throw new ChatPolicyViolation("CHAT_LENGTH_INVALID");
        }
        if (rawBody.codePoints().anyMatch(Character::isISOControl)) {
            throw new ChatPolicyViolation("CHAT_CONTROL_CHARACTER");
        }
        var body = rawBody.strip();
        var length = body.codePointCount(0, body.length());
        if (length < 1 || length > MAX_BODY_CODE_POINTS) {
            throw new ChatPolicyViolation("CHAT_LENGTH_INVALID");
        }
        if (URL.matcher(body).find()) {
            throw new ChatPolicyViolation("CHAT_URL_NOT_ALLOWED");
        }
        return body;
    }

    private static UUID canonicalRequestId(String requestId) {
        if (requestId == null || requestId.length() != 36) {
            throw new ChatPolicyViolation("CHAT_REQUEST_ID_INVALID");
        }
        try {
            var value = UUID.fromString(requestId);
            if (!value.toString().equalsIgnoreCase(requestId)) {
                throw new ChatPolicyViolation("CHAT_REQUEST_ID_INVALID");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new ChatPolicyViolation("CHAT_REQUEST_ID_INVALID");
        }
    }

    private void remember(RequestKey request) {
        processedRequests.add(request);
        processedRequestOrder.addLast(request);
        if (processedRequestOrder.size() > REMEMBERED_REQUEST_LIMIT) {
            processedRequests.remove(processedRequestOrder.removeFirst());
        }
    }

    public record AcceptedChat(long sequence, ChatMessage message) {
    }

    public record ChatMessage(
            UUID messageId,
            String actorId,
            String nickname,
            String body,
            Instant sentAt
    ) {
    }

    public static final class ChatPolicyViolation extends RuntimeException {
        private final String code;

        public ChatPolicyViolation(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record RateKey(RoomId roomId, ActorId actorId) {
    }

    private record TokenBucket(double tokens, Instant refilledAt) {
    }

    private record RequestKey(RoomId roomId, ActorId actorId, UUID requestId) {
    }
}
