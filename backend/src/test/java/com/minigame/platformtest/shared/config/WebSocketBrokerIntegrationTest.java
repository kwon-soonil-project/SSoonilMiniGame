package com.minigame.platformtest.shared.config;

import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomSettings;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.config.WebSocketConfig;
import com.minigame.platform.shared.realtime.EventEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(WebSocketBrokerIntegrationTest.BrokerTestConfig.class)
@WebAppConfiguration
@TestPropertySource(properties = "app.websocket.allowed-origins=http://localhost:8080")
class WebSocketBrokerIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T04:00:00Z"), ZoneOffset.UTC);
    private static final ActorPrincipal ACTOR = ActorPrincipal.guest(new ActorId("broker-actor"), "브로커감자");
    private static final RoomId ROOM_ID = new RoomId(
            UUID.fromString("00000000-0000-0000-0000-000000005301")
    );
    private static final String SESSION_ID = "broker-session";
    private static final String DESTINATION = "/topic/rooms/" + ROOM_ID.value();
    private static final Pattern SEQUENCE_JSON = Pattern.compile("\\\"sequence\\\":(\\d+)");

    @Autowired
    ActiveRoomRepository rooms;

    @Autowired
    SimpleBrokerMessageHandler simpleBroker;

    @Autowired
    @Qualifier("clientInboundChannel")
    MessageChannel clientInbound;

    @Autowired
    @Qualifier("clientOutboundChannel")
    SubscribableChannel clientOutbound;

    @Autowired
    @Qualifier("brokerMessagingTemplate")
    SimpMessagingTemplate brokerMessaging;

    private MessageHandler outboundHandler;

    @BeforeEach
    void setUpRoom() throws Exception {
        rooms.findAll().forEach(room -> rooms.remove(room.id()));
        rooms.save(Room.create(
                ROOM_ID,
                new RoomCode("530100"),
                "broker room",
                Visibility.PUBLIC,
                new RoomSettings(GameType.LIAR, 10, 3, 30, 90, "all"),
                ACTOR.actorId(),
                ACTOR.nickname()
        ));
    }

    @AfterEach
    void removeHandler() {
        if (outboundHandler != null) {
            clientInbound.send(stomp(StompCommand.DISCONNECT, null, null));
            clientOutbound.unsubscribe(outboundHandler);
        }
    }

    @Test
    void preservesSequenceThroughTheActualBrokerAndClientOutboundPath() throws Exception {
        assertThat(simpleBroker.isPreservePublishOrder()).isTrue();
        var sequences = new CopyOnWriteArrayList<Long>();
        var delivered = new CountDownLatch(2);
        connectAndSubscribe(message -> {
            var sequence = outboundSequence(message);
            if (sequence != null) {
                if (sequence == 1L) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                sequences.add(sequence);
                delivered.countDown();
            }
        });

        brokerMessaging.convertAndSend(DESTINATION, event(1L));
        brokerMessaging.convertAndSend(DESTINATION, event(2L));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sequences).containsExactly(1L, 2L);
    }

    @Test
    void dropsActualOutboundRoomDeliveryAfterTheSubscriberLeaves() throws Exception {
        var delivery = new AtomicReference<>(new CountDownLatch(1));
        connectAndSubscribe(message -> {
            if (outboundSequence(message) != null) {
                delivery.get().countDown();
            }
        });

        brokerMessaging.convertAndSend(DESTINATION, event(1L));
        assertThat(delivery.get().await(5, TimeUnit.SECONDS)).isTrue();

        delivery.set(new CountDownLatch(1));
        rooms.withRoom(ROOM_ID, room -> room.leave(
                ACTOR.actorId(),
                "00000000-0000-0000-0000-000000005302"
        ));

        brokerMessaging.convertAndSend(DESTINATION, event(2L));

        assertThat(delivery.get().await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    private void connectAndSubscribe(MessageHandler handler) throws Exception {
        var connected = new CountDownLatch(1);
        outboundHandler = message -> {
            var accessor = StompHeaderAccessor.wrap(message);
            if (accessor.getMessageType() == SimpMessageType.CONNECT_ACK) {
                connected.countDown();
            }
            handler.handleMessage(message);
        };
        clientOutbound.subscribe(outboundHandler);
        clientInbound.send(stomp(StompCommand.CONNECT, null, null));
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        var subscription = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscription.setSessionId(SESSION_ID);
        subscription.setUser(ACTOR);
        subscription.setDestination(DESTINATION);
        subscription.setSubscriptionId("room-subscription");
        subscription.setLeaveMutable(true);
        clientInbound.send(MessageBuilder.createMessage(new byte[0], subscription.getMessageHeaders()));
        assertThat(awaitSubscription()).isTrue();
    }

    private boolean awaitSubscription() {
        var lookup = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        lookup.setDestination(DESTINATION);
        lookup.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], lookup.getMessageHeaders());
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var subscriptions = simpleBroker.getSubscriptionRegistry().findSubscriptions(message);
            if (subscriptions.getOrDefault(SESSION_ID, List.of()).contains("room-subscription")) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return false;
    }

    private static Long outboundSequence(Message<?> message) {
        var accessor = StompHeaderAccessor.wrap(message);
        if (!DESTINATION.equals(accessor.getDestination()) || !(message.getPayload() instanceof byte[] bytes)) {
            return null;
        }
        var matcher = SEQUENCE_JSON.matcher(new String(bytes, StandardCharsets.UTF_8));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static Message<byte[]> stomp(StompCommand command, String destination, String subscriptionId) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setUser(ACTOR);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static EventEnvelope<Map<String, Long>> event(long sequence) {
        return EventEnvelope.create(
                UUID.nameUUIDFromBytes(("broker-" + sequence).getBytes(StandardCharsets.UTF_8)).toString(),
                ROOM_ID,
                ACTOR,
                "BROKER_TEST",
                sequence,
                CLOCK,
                Map.of("sequence", sequence)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(WebSocketConfig.class)
    static class BrokerTestConfig {
        @Bean
        Clock clock() {
            return CLOCK;
        }

        @Bean
        ActiveRoomRepository activeRoomRepository() {
            return new InMemoryActiveRoomRepository();
        }

        @Bean
        SessionTokenService sessionTokenService() {
            return SessionTokenService.hmac(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                    CLOCK
            );
        }
    }
}
