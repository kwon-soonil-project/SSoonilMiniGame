package com.minigame.platform.shared.realtime;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.domain.RoomId;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoomEventPublisherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T03:00:00Z"), ZoneOffset.UTC);
    private static final RoomId ROOM_ID = new RoomId(
            UUID.fromString("00000000-0000-0000-0000-000000005201")
    );
    private static final ActorPrincipal ACTOR = ActorPrincipal.guest(
            new ActorId("publisher-actor"),
            "발행감자"
    );

    @Test
    void serializesTheVersionedEnvelopeContract() {
        var event = EventEnvelope.create(
                "00000000-0000-0000-0000-000000005202",
                ROOM_ID,
                ACTOR,
                "PLAYER_READY_CHANGED",
                3L,
                CLOCK,
                Map.of("ready", true)
        );

        var json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(event);

        assertThat(json)
                .contains("\"version\":1")
                .contains("\"eventId\":\"" + event.eventId() + "\"")
                .contains("\"requestId\":\"00000000-0000-0000-0000-000000005202\"")
                .contains("\"roomId\":\"00000000-0000-0000-0000-000000005201\"")
                .contains("\"actorId\":\"publisher-actor\"")
                .contains("\"type\":\"PLAYER_READY_CHANGED\"")
                .contains("\"sequence\":3")
                .contains("\"occurredAt\":\"2026-08-23T03:00:00Z\"")
                .contains("\"payload\":{\"ready\":true}");
    }

    @Test
    void routesPublicPrivateAndLobbyEventsToTheirStableDestinations() {
        var channel = new RecordingChannel();
        var publisher = new StompRoomEventPublisher(new SimpMessagingTemplate(channel));
        var event = EventEnvelope.create(
                "00000000-0000-0000-0000-000000005203",
                ROOM_ID,
                ACTOR,
                "TEST_EVENT",
                4L,
                CLOCK,
                Map.of()
        );

        publisher.publishPublic(event);
        publisher.publishPrivate(ACTOR.getName(), event);
        publisher.publishLobby(event);

        assertThat(channel.destinations()).containsExactly(
                "/topic/rooms/00000000-0000-0000-0000-000000005201",
                "/user/publisher-actor/queue/rooms/00000000-0000-0000-0000-000000005201",
                "/topic/lobby"
        );
        assertThat(channel.messages).hasSize(3).allSatisfy(message ->
                assertThat(message.getPayload()).isEqualTo(event)
        );
    }

    private static final class RecordingChannel implements MessageChannel {
        private final List<Message<?>> messages = new ArrayList<>();

        @Override
        public boolean send(Message<?> message) {
            messages.add(message);
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return send(message);
        }

        List<String> destinations() {
            return messages.stream()
                    .map(message -> (String) message.getHeaders().get(SimpMessageHeaderAccessor.DESTINATION_HEADER))
                    .toList();
        }
    }
}
