package com.minigame.platform.shared.config;

import com.minigame.platform.auth.adapter.in.web.SessionCookieAuthenticationFilter;
import com.minigame.platform.auth.application.InvalidSessionTokenException;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.domain.RoomId;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final SessionTokenService tokenService;
    private final ActiveRoomRepository rooms;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            SessionTokenService tokenService,
            ActiveRoomRepository rooms,
            @Value("${app.websocket.allowed-origins}") String allowedOrigins
    ) {
        this.tokenService = tokenService;
        this.rooms = rooms;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        if (this.allowedOrigins.length == 0 || Arrays.asList(this.allowedOrigins).contains("*")) {
            throw new IllegalArgumentException("Explicit WebSocket origins are required");
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(new AppSessionHandshakeInterceptor(tokenService))
                .setHandshakeHandler(new ActorHandshakeHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ActorMessageBoundaryInterceptor(rooms));
    }
}

final class AppSessionHandshakeInterceptor implements HandshakeInterceptor {
    static final String PRINCIPAL_ATTRIBUTE = AppSessionHandshakeInterceptor.class.getName() + ".principal";

    private final SessionTokenService tokenService;

    AppSessionHandshakeInterceptor(SessionTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            reject(response);
            return false;
        }
        var cookies = servletRequest.getServletRequest().getCookies();
        if (cookies == null) {
            reject(response);
            return false;
        }
        var token = Arrays.stream(cookies)
                .filter(cookie -> SessionCookieAuthenticationFilter.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
        if (token.isEmpty()) {
            reject(response);
            return false;
        }
        try {
            attributes.put(PRINCIPAL_ATTRIBUTE, tokenService.verify(token.orElseThrow()));
            return true;
        } catch (InvalidSessionTokenException exception) {
            reject(response);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    private static void reject(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }
}

final class ActorHandshakeHandler extends DefaultHandshakeHandler {
    @Override
    protected Principal determineUser(
            ServerHttpRequest request,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        var principal = attributes.get(AppSessionHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        return principal instanceof ActorPrincipal actor ? actor : null;
    }
}

final class ActorMessageBoundaryInterceptor implements org.springframework.messaging.support.ChannelInterceptor {
    private static final Pattern ROOM_SUBSCRIPTION = Pattern.compile(
            "^/(?:topic|user/queue)/rooms/([0-9a-fA-F-]{36})$"
    );
    private static final Pattern ROOM_COMMAND = Pattern.compile(
            "^/app/rooms/([0-9a-fA-F-]{36})/commands$"
    );

    private final ActiveRoomRepository rooms;

    ActorMessageBoundaryInterceptor(ActiveRoomRepository rooms) {
        this.rooms = rooms;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = StompHeaderAccessor.wrap(message);
        var command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        if (command == StompCommand.CONNECT
                || command == StompCommand.SEND
                || command == StompCommand.SUBSCRIBE) {
            if (!(accessor.getUser() instanceof ActorPrincipal actor)) {
                throw new AccessDeniedException("APP_SESSION principal required");
            }
            authorizeDestination(command, accessor.getDestination(), actor);
        }
        return message;
    }

    private void authorizeDestination(StompCommand command, String destination, ActorPrincipal actor) {
        if (command == StompCommand.CONNECT) {
            return;
        }
        if (command == StompCommand.SUBSCRIBE && "/topic/lobby".equals(destination)) {
            return;
        }
        var matcher = (command == StompCommand.SEND ? ROOM_COMMAND : ROOM_SUBSCRIPTION)
                .matcher(destination == null ? "" : destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("STOMP destination is not allowed");
        }
        final RoomId roomId;
        try {
            roomId = new RoomId(UUID.fromString(matcher.group(1)));
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Invalid room destination", exception);
        }
        var participant = rooms.findById(roomId)
                .stream()
                .flatMap(room -> room.participants().stream())
                .anyMatch(candidate -> candidate.actorId().equals(actor.actorId()));
        if (!participant) {
            throw new AccessDeniedException("Room participant required");
        }
    }
}
