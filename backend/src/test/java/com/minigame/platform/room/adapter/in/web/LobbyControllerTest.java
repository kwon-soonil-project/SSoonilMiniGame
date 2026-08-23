package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.room.domain.GameType;
import com.minigame.platform.room.domain.Room;
import com.minigame.platform.room.domain.RoomId;
import com.minigame.platform.room.domain.RoomSettings;
import com.minigame.platform.room.domain.Visibility;
import com.minigame.platform.shared.error.GlobalExceptionHandler;
import com.minigame.platform.shared.config.SecurityConfig;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import com.minigame.platform.shared.abuse.AbuseRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = LobbyController.class,
        properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long"
)
@Import({
        SecurityConfig.class,
        AbuseRateLimiter.class,
        ChatPolicy.class,
        RoomApplicationService.class,
        InMemoryActiveRoomRepository.class,
        GlobalExceptionHandler.class
})
class LobbyControllerTest {
    private static final ActorPrincipal VIEWER = ActorPrincipal.guest(new ActorId("viewer-api"), "구경감자");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ActiveRoomRepository repository;

    @Autowired
    SessionTokenService tokenService;

    @MockitoBean
    RoomEventPublisher eventPublisher;

    @BeforeEach
    void setUpRooms() {
        repository.findAll().forEach(room -> repository.remove(room.id()));
        repository.save(room("공개 라이어", Visibility.PUBLIC, GameType.LIAR, "111111"));
        repository.save(room("공개 그림", Visibility.PUBLIC, GameType.DRAWING, "222222"));
        repository.save(room("숨은 초성", Visibility.PRIVATE, GameType.CHOSUNG, "333333"));
    }

    @Test
    void returnsOnlyPublicRoomsWithLobbyFieldsAndNoPasswordMaterial() throws Exception {
        mockMvc.perform(get("/api/v1/lobby/rooms").cookie(session(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].roomId").isString())
                .andExpect(jsonPath("$[0].code").isString())
                .andExpect(jsonPath("$[0].participantCount").value(1))
                .andExpect(jsonPath("$[0].sequence").value(0))
                .andExpect(jsonPath("$[0].hostNickname").value("방장감자"))
                .andExpect(jsonPath("$[0].passwordProtected").value(false))
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void filtersPublicLobbyBySearchAndGameType() throws Exception {
        mockMvc.perform(get("/api/v1/lobby/rooms")
                        .cookie(session(VIEWER))
                        .queryParam("query", "그림")
                        .queryParam("gameType", "DRAWING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("공개 그림"))
                .andExpect(jsonPath("$[0].gameType").value("DRAWING"));
    }

    private Room room(String title, Visibility visibility, GameType gameType, String code) {
        return Room.create(
                RoomId.random(),
                new com.minigame.platform.room.domain.RoomCode(code),
                title,
                visibility,
                new RoomSettings(gameType, gameType.maximumParticipants(), 3, 30, 90, "all"),
                new ActorId("host-" + code),
                "방장감자"
        );
    }

    private Cookie session(ActorPrincipal actor) {
        return new Cookie("APP_SESSION", tokenService.issue(actor, Duration.ofHours(1)));
    }

}
