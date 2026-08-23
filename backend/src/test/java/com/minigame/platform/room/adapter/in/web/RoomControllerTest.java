package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.shared.config.SecurityConfig;
import com.minigame.platform.shared.error.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = RoomController.class,
        properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long"
)
@Import({
        SecurityConfig.class,
        RoomApplicationService.class,
        InMemoryActiveRoomRepository.class,
        GlobalExceptionHandler.class
})
class RoomControllerTest {
    private static final ActorPrincipal HOST = ActorPrincipal.guest(new ActorId("host-api"), "방장감자");
    private static final ActorPrincipal GUEST = ActorPrincipal.guest(new ActorId("guest-api"), "참가감자");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ActiveRoomRepository repository;

    @Autowired
    SessionTokenService tokenService;

    @BeforeEach
    void clearRooms() {
        repository.findAll().forEach(room -> repository.remove(room.id()));
    }

    @Test
    void createsRoomWithGameCapacityAndNeverReturnsPasswordMaterial() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .cookie(session(HOST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"퇴근 후 딱 한 판!","visibility":"PUBLIC","password":"1234","gameType":"LIAR"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").isString())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.gameType").value("LIAR"))
                .andExpect(jsonPath("$.maxParticipants").value(10))
                .andExpect(jsonPath("$.participantCount").value(1))
                .andExpect(jsonPath("$.passwordProtected").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void rejectsWrongPasswordWithStableCodeAndCorrelationId() throws Exception {
        var room = createRoom(HOST, "비밀방", "1234", "LIAR");

        mockMvc.perform(post("/api/v1/rooms/{code}/join", room.code())
                        .cookie(session(GUEST))
                        .header("X-Request-Id", "request-wrong-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"9999\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROOM_PASSWORD_INVALID"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").value("request-wrong-password"));
    }

    @Test
    void joinsSnapshotsAndLeavesRoom() throws Exception {
        var room = createRoom(HOST, "같이하는 방", "1234", "DRAWING");

        mockMvc.perform(post("/api/v1/rooms/{code}/join", room.code())
                        .cookie(session(GUEST))
                        .header("X-Request-Id", "request-join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.participants", hasSize(2)))
                .andExpect(jsonPath("$.passwordProtected").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(get("/api/v1/rooms/{roomId}/snapshot", room.roomId())
                        .cookie(session(GUEST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.participants[1].actorId").value("guest-api"));

        mockMvc.perform(post("/api/v1/rooms/{roomId}/leave", room.roomId())
                        .cookie(session(GUEST))
                        .header("X-Request-Id", "request-leave"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/rooms/{roomId}/snapshot", room.roomId())
                        .cookie(session(HOST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(1));
    }

    @Test
    void rejectsJoinWhenRoomIsFull() throws Exception {
        var room = createRoom(HOST, "만원인 방", null, "LIAR");
        for (int index = 1; index < 10; index++) {
            var participant = ActorPrincipal.guest(new ActorId("full-" + index), "참가자" + index);
            mockMvc.perform(post("/api/v1/rooms/{code}/join", room.code())
                            .cookie(session(participant))
                            .header("X-Request-Id", "fill-" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/rooms/{code}/join", room.code())
                        .cookie(session(GUEST))
                        .header("X-Request-Id", "request-full")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_FULL"))
                .andExpect(jsonPath("$.requestId").value("request-full"));
    }

    @Test
    void mapsMissingRoomsAndInvalidInputToStableErrors() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/000000/join")
                        .cookie(session(GUEST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId", not(blankOrNullString())));

        mockMvc.perform(post("/api/v1/rooms")
                        .cookie(session(HOST))
                        .header("X-Request-Id", "request-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"","visibility":"PUBLIC","password":"123456789012345678901","gameType":"LIAR"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("request-invalid"));
    }

    private CreatedRoom createRoom(
            ActorPrincipal host,
            String title,
            String password,
            String gameType
    ) throws Exception {
        var passwordJson = password == null ? "null" : "\"" + password + "\"";
        var result = mockMvc.perform(post("/api/v1/rooms")
                        .cookie(session(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"%s","visibility":"PUBLIC","password":%s,"gameType":"%s"}
                            """.formatted(title, passwordJson, gameType)))
                .andExpect(status().isCreated())
                .andReturn();
        var json = result.getResponse().getContentAsString();
        var roomId = json.replaceAll(".*\"roomId\":\"([^\"]+)\".*", "$1");
        var code = json.replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
        return new CreatedRoom(roomId, code);
    }

    record CreatedRoom(String roomId, String code) {
    }

    private Cookie session(ActorPrincipal actor) {
        return new Cookie("APP_SESSION", tokenService.issue(actor, Duration.ofHours(1)));
    }

}

@WebMvcTest(
        controllers = RoomController.class,
        properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long"
)
@Import({
        SecurityConfig.class,
        RoomApplicationService.class,
        InMemoryActiveRoomRepository.class,
        GlobalExceptionHandler.class
})
class RoomControllerSecurityTest {
    private static final ActorPrincipal HOST = ActorPrincipal.guest(
            new ActorId("secure-host-api"),
            "보안감자"
    );

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionTokenService tokenService;

    @Test
    void applicationSessionCanCreateRoomWithoutASeparateCsrfToken() throws Exception {
        var token = tokenService.issue(HOST, Duration.ofHours(1));

        mockMvc.perform(post("/api/v1/rooms")
                        .cookie(new Cookie("APP_SESSION", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"보안 경계 방","visibility":"PUBLIC","gameType":"LIAR"}
                            """))
                .andExpect(status().isCreated());
    }
}
