package com.minigame.platform.room.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Component
public final class RoomPresenceService {
    private final Supplier<RoomApplicationService> rooms;
    private final DisconnectScheduler scheduler;
    private final Duration disconnectGrace;
    private final Map<String, ActorPrincipal> sessions = new HashMap<>();
    private final Map<ActorId, Set<String>> actorSessions = new HashMap<>();
    private final Map<ActorId, PendingLeave> pendingLeaves = new HashMap<>();

    @Autowired
    public RoomPresenceService(
            ObjectProvider<RoomApplicationService> rooms,
            @Qualifier("roomDisconnectTaskScheduler") TaskScheduler scheduler,
            @Value("${app.websocket.disconnect-grace:PT30S}") String disconnectGrace
    ) {
        this(
                rooms::getObject,
                (delay, task) -> {
                    var future = scheduler.schedule(task, Instant.now().plus(delay));
                    if (future == null) {
                        throw new IllegalStateException("Disconnect grace could not be scheduled");
                    }
                    return () -> future.cancel(false);
                },
                Duration.parse(disconnectGrace)
        );
    }

    public RoomPresenceService(
            RoomApplicationService rooms,
            TaskScheduler scheduler,
            Duration disconnectGrace
    ) {
        this(
                () -> rooms,
                (delay, task) -> {
                    var future = scheduler.schedule(task, Instant.now().plus(delay));
                    if (future == null) {
                        throw new IllegalStateException("Disconnect grace could not be scheduled");
                    }
                    return () -> future.cancel(false);
                },
                disconnectGrace
        );
    }

    RoomPresenceService(
            RoomApplicationService rooms,
            DisconnectScheduler scheduler,
            Duration disconnectGrace
    ) {
        this(() -> rooms, scheduler, disconnectGrace);
    }

    private RoomPresenceService(
            Supplier<RoomApplicationService> rooms,
            DisconnectScheduler scheduler,
            Duration disconnectGrace
    ) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (disconnectGrace == null || disconnectGrace.isNegative()) {
            throw new IllegalArgumentException("Disconnect grace cannot be negative");
        }
        this.disconnectGrace = disconnectGrace;
    }

    public synchronized void connected(String sessionId, ActorPrincipal actor) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("STOMP session ID required");
        }
        Objects.requireNonNull(actor, "actor");
        var previous = sessions.put(sessionId, actor);
        if (previous != null && !previous.actorId().equals(actor.actorId())) {
            removeSession(previous.actorId(), sessionId);
        }
        actorSessions.computeIfAbsent(actor.actorId(), ignored -> new HashSet<>()).add(sessionId);
        var pending = pendingLeaves.remove(actor.actorId());
        if (pending != null) {
            pending.cancel();
        }
    }

    public synchronized void disconnected(String sessionId) {
        if (sessionId == null) {
            return;
        }
        var actor = sessions.remove(sessionId);
        if (actor == null) {
            return;
        }
        removeSession(actor.actorId(), sessionId);
        if (actorSessions.containsKey(actor.actorId()) || pendingLeaves.containsKey(actor.actorId())) {
            return;
        }
        var pending = new PendingLeave(new Object());
        pendingLeaves.put(actor.actorId(), pending);
        var cancellation = scheduler.schedule(
                disconnectGrace,
                () -> expire(actor, pending.marker())
        );
        pending.attach(cancellation);
    }

    public synchronized Optional<ActorPrincipal> find(String sessionId) {
        return Optional.ofNullable(sessionId).map(sessions::get);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    private synchronized void expire(ActorPrincipal actor, Object marker) {
        var pending = pendingLeaves.get(actor.actorId());
        if (pending == null || pending.marker() != marker || actorSessions.containsKey(actor.actorId())) {
            return;
        }
        pendingLeaves.remove(actor.actorId());
        rooms.get().leaveJoinedRooms(actor);
    }

    private void removeSession(ActorId actorId, String sessionId) {
        var active = actorSessions.get(actorId);
        if (active == null) {
            return;
        }
        active.remove(sessionId);
        if (active.isEmpty()) {
            actorSessions.remove(actorId);
        }
    }

    @FunctionalInterface
    interface DisconnectScheduler {
        Cancellation schedule(Duration delay, Runnable task);
    }

    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }

    private static final class PendingLeave {
        private final Object marker;
        private Cancellation cancellation = () -> { };

        private PendingLeave(Object marker) {
            this.marker = marker;
        }

        Object marker() {
            return marker;
        }

        void attach(Cancellation cancellation) {
            this.cancellation = cancellation;
        }

        void cancel() {
            cancellation.cancel();
        }
    }
}
