package com.minigame.platform.shared.config;

import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.domain.RoomFixture;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.application.RoomPresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSocketConfigTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC);
    private static final ActorPrincipal ACTOR = ActorPrincipal.guest(new ActorId("socket-actor"), "소켓감자");

    @Test
    void authenticatesHandshakeOnlyFromTheApplicationSessionCookie() throws Exception {
        var tokens = SessionTokenService.hmac("0123456789abcdef0123456789abcdef".getBytes(), CLOCK);
        var interceptor = new AppSessionHandshakeInterceptor(tokens);
        var servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setCookies(new jakarta.servlet.http.Cookie(
                "APP_SESSION",
                tokens.issue(ACTOR, Duration.ofHours(1))
        ));
        var servletResponse = new MockHttpServletResponse();
        var attributes = new HashMap<String, Object>();

        var accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                new TextWebSocketHandler(),
                attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes.get(AppSessionHandshakeInterceptor.PRINCIPAL_ATTRIBUTE)).isEqualTo(ACTOR);
    }

    @Test
    void rejectsHandshakeWithoutAValidApplicationSession() throws Exception {
        var tokens = SessionTokenService.hmac("0123456789abcdef0123456789abcdef".getBytes(), CLOCK);
        var interceptor = new AppSessionHandshakeInterceptor(tokens);
        var servletResponse = new MockHttpServletResponse();

        var accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/ws")),
                new ServletServerHttpResponse(servletResponse),
                new TextWebSocketHandler(),
                new HashMap<>()
        );

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void rejectsProviderPrincipalAtTheStompMessageBoundary() {
        var repository = new InMemoryActiveRoomRepository();
        var interceptor = new ActorMessageBoundaryInterceptor(repository);
        Principal providerPrincipal = () -> "oauth-provider-user";
        var message = stompMessage(
                org.springframework.messaging.simp.stomp.StompCommand.SEND,
                "/app/rooms/" + RoomFixture.ROOM_ID.value() + "/commands",
                providerPrincipal
        );

        assertThatThrownBy(() -> interceptor.preSend(message, TestMessageChannel.INSTANCE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void permitsRoomSubscriptionsOnlyForCurrentParticipants() {
        var repository = new InMemoryActiveRoomRepository();
        repository.save(RoomFixture.emptyRoom());
        var interceptor = new ActorMessageBoundaryInterceptor(repository);
        var participant = ActorPrincipal.guest(RoomFixture.HOST, "방장감자");
        var outsider = ActorPrincipal.guest(new ActorId("socket-outsider"), "바깥감자");
        var destination = "/topic/rooms/" + RoomFixture.ROOM_ID.value();

        assertThat(interceptor.preSend(
                stompMessage(org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE, destination, participant),
                TestMessageChannel.INSTANCE
        )).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE, destination, outsider),
                TestMessageChannel.INSTANCE
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void stompConnectAndDisconnectDriveTheSharedPresenceRegistry() {
        var repository = new InMemoryActiveRoomRepository();
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        try {
            var presence = new RoomPresenceService(
                    mock(RoomApplicationService.class), scheduler, Duration.ofHours(1)
            );
            var interceptor = new ActorMessageBoundaryInterceptor(repository, presence);

            interceptor.preSend(stompMessage(
                    org.springframework.messaging.simp.stomp.StompCommand.CONNECT, null, ACTOR, "session-1"
            ), TestMessageChannel.INSTANCE);
            assertThat(presence.find("session-1")).contains(ACTOR);

            interceptor.preSend(stompMessage(
                    org.springframework.messaging.simp.stomp.StompCommand.DISCONNECT, null, ACTOR, "session-1"
            ), TestMessageChannel.INSTANCE);
            assertThat(presence.find("session-1")).isEmpty();
        } finally {
            scheduler.shutdown();
        }
    }

    private static org.springframework.messaging.Message<byte[]> stompMessage(
            org.springframework.messaging.simp.stomp.StompCommand command,
            String destination,
            Principal principal
    ) {
        return stompMessage(command, destination, principal, null);
    }

    private static org.springframework.messaging.Message<byte[]> stompMessage(
            org.springframework.messaging.simp.stomp.StompCommand command,
            String destination,
            Principal principal,
            String sessionId
    ) {
        var accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        if (sessionId != null) accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private enum TestMessageChannel implements MessageChannel {
        INSTANCE;

        @Override
        public boolean send(Message<?> message) {
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return true;
        }
    }
}
