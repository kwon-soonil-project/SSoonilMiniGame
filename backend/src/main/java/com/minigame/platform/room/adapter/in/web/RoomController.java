package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.domain.RoomCode;
import com.minigame.platform.room.domain.RoomId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.minigame.platform.shared.error.RequestIds.commandId;

import static com.minigame.platform.room.adapter.in.web.RoomWebDtos.CreateRoomRequest;
import static com.minigame.platform.room.adapter.in.web.RoomWebDtos.JoinRoomRequest;
import static com.minigame.platform.room.adapter.in.web.RoomWebDtos.RoomSnapshotResponse;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {
    private final RoomApplicationService rooms;

    public RoomController(RoomApplicationService rooms) {
        this.rooms = rooms;
    }

    @PostMapping
    ResponseEntity<RoomSnapshotResponse> create(
            @AuthenticationPrincipal ActorPrincipal actor,
            @Valid @RequestBody CreateRoomRequest request,
            HttpServletRequest servletRequest
    ) {
        var view = rooms.create(
                actor,
                request.title(),
                request.visibility(),
                request.password(),
                request.gameType(),
                commandId(servletRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(RoomWebDtos.from(view));
    }

    @PostMapping("/{code}/join")
    RoomSnapshotResponse join(
            @AuthenticationPrincipal ActorPrincipal actor,
            @PathVariable String code,
            @Valid @RequestBody JoinRoomRequest request,
            HttpServletRequest servletRequest
    ) {
        return RoomWebDtos.from(
                rooms.join(actor, new RoomCode(code), request.password(), commandId(servletRequest))
        );
    }

    @GetMapping("/{roomId}/snapshot")
    RoomSnapshotResponse snapshot(
            @AuthenticationPrincipal ActorPrincipal actor,
            @PathVariable UUID roomId
    ) {
        return RoomWebDtos.from(rooms.snapshot(actor, new RoomId(roomId)));
    }

    @PostMapping("/{roomId}/leave")
    ResponseEntity<Void> leave(
            @AuthenticationPrincipal ActorPrincipal actor,
            @PathVariable UUID roomId,
            HttpServletRequest servletRequest
    ) {
        rooms.leave(actor, new RoomId(roomId), commandId(servletRequest));
        return ResponseEntity.noContent().build();
    }
}
