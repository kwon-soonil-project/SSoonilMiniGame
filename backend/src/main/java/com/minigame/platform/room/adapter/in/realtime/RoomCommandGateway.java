package com.minigame.platform.room.adapter.in.realtime;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomRuleViolation;
import com.minigame.platform.room.domain.RoomSettings;
import com.minigame.platform.shared.realtime.EventEnvelope;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Controller
public class RoomCommandGateway {
    private final RoomApplicationService rooms;
    private final ChatPolicy chatPolicy;
    private final RoomEventPublisher publisher;
    private final Clock clock;

    public RoomCommandGateway(
            RoomApplicationService rooms,
            ChatPolicy chatPolicy,
            RoomEventPublisher publisher,
            Clock clock
    ) {
        this.rooms = rooms;
        this.chatPolicy = chatPolicy;
        this.publisher = publisher;
        this.clock = clock;
    }

    @MessageMapping("/rooms/{roomId}/commands")
    public void handle(
            @DestinationVariable UUID roomId,
            Principal principal,
            @Payload RoomCommands.RoomCommand command
    ) {
        if (!(principal instanceof ActorPrincipal actor)) {
            throw new AccessDeniedException("APP_SESSION principal required");
        }
        var domainRoomId = new RoomId(roomId);
        try {
            switch (command.type()) {
                case "PLAYER_READY" -> rooms.changeReady(
                        actor,
                        domainRoomId,
                        booleanValue(command.payload(), "ready"),
                        command.requestId()
                );
                case "ROOM_SETTINGS_UPDATE" -> updateSettings(actor, domainRoomId, command);
                case "CHAT_SEND" -> sendChat(actor, domainRoomId, command);
                default -> throw new CommandViolation("ROOM_COMMAND_UNSUPPORTED");
            }
        } catch (ChatPolicy.ChatPolicyViolation exception) {
            reject(actor, domainRoomId, command.requestId(), exception.code());
        } catch (RoomRuleViolation exception) {
            reject(actor, domainRoomId, command.requestId(), exception.code());
        } catch (CommandViolation exception) {
            reject(actor, domainRoomId, command.requestId(), exception.code());
        } catch (IllegalArgumentException exception) {
            reject(actor, domainRoomId, command.requestId(), "ROOM_COMMAND_INVALID");
        }
    }

    private void sendChat(
            ActorPrincipal actor,
            RoomId roomId,
            RoomCommands.RoomCommand command
    ) {
        var rawBody = stringValue(command.payload(), "body");
        chatPolicy.accept(
                roomId,
                actor,
                command.requestId(),
                rawBody,
                message -> rooms.publishChat(actor, roomId, command.requestId(), message)
        );
    }

    private void updateSettings(
            ActorPrincipal actor,
            RoomId roomId,
            RoomCommands.RoomCommand command
    ) {
        var current = rooms.snapshot(actor, roomId);
        rooms.updateSettings(
                actor,
                roomId,
                new RoomSettings(
                        current.gameType(),
                        intValue(command.payload(), "maxParticipants"),
                        intValue(command.payload(), "rounds"),
                        intValue(command.payload(), "actionSeconds"),
                        intValue(command.payload(), "discussionSeconds"),
                        stringValue(command.payload(), "categoryPack")
                ),
                command.requestId()
        );
    }

    private void reject(ActorPrincipal actor, RoomId roomId, String requestId, String code) {
        publisher.publishPrivate(
                actor.getName(),
                EventEnvelope.create(
                        safeRequestId(requestId),
                        roomId,
                        actor,
                        "COMMAND_REJECTED",
                        currentSequence(actor, roomId),
                        clock,
                        Map.of("code", code)
                )
        );
    }

    private long currentSequence(ActorPrincipal actor, RoomId roomId) {
        try {
            return rooms.snapshot(actor, roomId).sequence();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String safeRequestId(String requestId) {
        if (requestId != null && requestId.length() == 36) {
            try {
                var parsed = UUID.fromString(requestId);
                if (parsed.toString().equalsIgnoreCase(requestId)) {
                    return parsed.toString();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.randomUUID().toString();
    }

    private static boolean booleanValue(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new CommandViolation("ROOM_COMMAND_INVALID");
    }

    private static int intValue(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new CommandViolation("ROOM_COMMAND_INVALID");
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        var value = payload.get(key);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new CommandViolation("ROOM_COMMAND_INVALID");
    }

    private static final class CommandViolation extends RuntimeException {
        private final String code;

        private CommandViolation(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
