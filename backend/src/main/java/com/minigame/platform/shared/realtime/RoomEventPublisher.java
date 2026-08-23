package com.minigame.platform.shared.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

public interface RoomEventPublisher {
    void publishPublic(EventEnvelope<?> event);

    void publishPrivate(String userName, EventEnvelope<?> event);

    void publishLobby(EventEnvelope<?> event);

    static RoomEventPublisher noOp() {
        return new RoomEventPublisher() {
            @Override
            public void publishPublic(EventEnvelope<?> event) {
            }

            @Override
            public void publishPrivate(String userName, EventEnvelope<?> event) {
            }

            @Override
            public void publishLobby(EventEnvelope<?> event) {
            }
        };
    }
}

@Component
final class StompRoomEventPublisher implements RoomEventPublisher {
    private final SimpMessagingTemplate messaging;

    StompRoomEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void publishPublic(EventEnvelope<?> event) {
        messaging.convertAndSend("/topic/rooms/" + event.roomId(), event);
    }

    @Override
    public void publishPrivate(String userName, EventEnvelope<?> event) {
        messaging.convertAndSendToUser(userName, "/queue/rooms/" + event.roomId(), event);
    }

    @Override
    public void publishLobby(EventEnvelope<?> event) {
        messaging.convertAndSend("/topic/lobby", event);
    }
}
