package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.domain.GameType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.minigame.platform.room.adapter.in.web.RoomWebDtos.LobbyRoomResponse;

@RestController
@RequestMapping("/api/v1/lobby")
public class LobbyController {
    private final RoomApplicationService rooms;

    public LobbyController(RoomApplicationService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/rooms")
    List<LobbyRoomResponse> rooms(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) GameType gameType,
            @RequestParam(required = false) Boolean available
    ) {
        return rooms.lobbyRooms(query, gameType, available).stream().map(RoomWebDtos::from).toList();
    }
}
