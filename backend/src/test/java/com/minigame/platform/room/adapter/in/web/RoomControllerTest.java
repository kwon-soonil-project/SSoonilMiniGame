package com.minigame.platform.room.adapter.in.web;

import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.adapter.in.web.CsrfController;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.shared.config.SecurityConfig;
import com.minigame.platform.shared.error.GlobalExceptionHandler;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {RoomController.class, CsrfController.class},
        properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long"
)
@Import({
        SecurityConfig.class,
        ChatPolicy.class,
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

    @MockitoBean
    RoomEventPublisher eventPublisher;

    @BeforeEach
    void clearRooms() {
        repository.findAll().forEach(room -> repository.remove(room.id()));
    }

    @Test
    void createsRoomWithGameCapacityAndNeverReturnsPasswordMaterial() throws Exception {
        mockMvc.perform(postWithCsrf("/api/v1/rooms", HOST)
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
        var requestId = requestId("wrong-password");

        mockMvc.perform(postWithCsrf("/api/v1/rooms/{code}/join", GUEST, room.code())
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"9999\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROOM_PASSWORD_INVALID"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void joinsSnapshotsAndLeavesRoom() throws Exception {
        var room = createRoom(HOST, "같이하는 방", "1234", "DRAWING");

        mockMvc.perform(postWithCsrf("/api/v1/rooms/{code}/join", GUEST, room.code())
                        .header("X-Request-Id", requestId("join"))
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

        mockMvc.perform(postWithCsrf("/api/v1/rooms/{roomId}/leave", GUEST, room.roomId())
                        .header("X-Request-Id", requestId("leave")))
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
            mockMvc.perform(postWithCsrf("/api/v1/rooms/{code}/join", participant, room.code())
                            .header("X-Request-Id", requestId("fill-" + index))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        var fullRequestId = requestId("full");
        mockMvc.perform(postWithCsrf("/api/v1/rooms/{code}/join", GUEST, room.code())
                        .header("X-Request-Id", fullRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_FULL"))
                .andExpect(jsonPath("$.requestId").value(fullRequestId));
    }

    @Test
    void mapsMissingRoomsAndInvalidInputToStableErrors() throws Exception {
        mockMvc.perform(postWithCsrf("/api/v1/rooms/000000/join", GUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId", not(blankOrNullString())));

        var validationRequestId = requestId("validation");
        mockMvc.perform(postWithCsrf("/api/v1/rooms", HOST)
                        .header("X-Request-Id", validationRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"","visibility":"PUBLIC","password":"123456789012345678901","gameType":"LIAR"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value(validationRequestId));

        var room = createRoom(HOST, "요청 ID 검증 방", null, "LIAR");
        mockMvc.perform(postWithCsrf("/api/v1/rooms/{code}/join", GUEST, room.code())
                        .header("X-Request-Id", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROOM_REQUEST_ID_INVALID"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    private CreatedRoom createRoom(
            ActorPrincipal host,
            String title,
            String password,
            String gameType
    ) throws Exception {
        var passwordJson = password == null ? "null" : "\"" + password + "\"";
        var result = mockMvc.perform(postWithCsrf("/api/v1/rooms", host)
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

    private MockHttpServletRequestBuilder postWithCsrf(
            String path,
            ActorPrincipal actor,
            Object... pathVariables
    ) throws Exception {
        var applicationSession = session(actor);
        var csrfResult = mockMvc.perform(get("/api/v1/csrf").cookie(applicationSession))
                .andExpect(status().isOk())
                .andReturn();
        var csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        return post(path, pathVariables)
                .cookie(applicationSession, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue());
    }

    private static String requestId(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8)).toString();
    }

}

@WebMvcTest(
        controllers = {RoomController.class, CsrfController.class},
        properties = "app.session.secret=test-key-that-is-at-least-32-bytes-long"
)
@Import({
        SecurityConfig.class,
        ChatPolicy.class,
        RoomApplicationService.class,
        InMemoryActiveRoomRepository.class,
        GlobalExceptionHandler.class
})
class RoomControllerSecurityTest {
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000009001";
    private static final ActorPrincipal HOST = ActorPrincipal.guest(
            new ActorId("secure-host-api"),
            "보안감자"
    );

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionTokenService tokenService;

    @MockitoBean
    RoomEventPublisher eventPublisher;

    @Test
    void rejectsAuthenticatedMutationWithoutCsrfTokenUsingApiErrorEnvelope() throws Exception {
        var token = tokenService.issue(HOST, Duration.ofHours(1));

        mockMvc.perform(post("/api/v1/rooms")
                        .cookie(new Cookie("APP_SESSION", token))
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"보안 경계 방","visibility":"PUBLIC","gameType":"LIAR"}
                            """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void joinAndLeaveAlsoRejectMissingCsrfToken() throws Exception {
        var session = new Cookie("APP_SESSION", tokenService.issue(HOST, Duration.ofHours(1)));

        mockMvc.perform(post("/api/v1/rooms/000000/join")
                        .cookie(session)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));

        mockMvc.perform(post("/api/v1/rooms/00000000-0000-0000-0000-000000000001/leave")
                        .cookie(session)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void issuesReadableCsrfCookieThatAuthorizesMutation() throws Exception {
        var token = tokenService.issue(HOST, Duration.ofHours(1));
        var session = new Cookie("APP_SESSION", token);
        var csrfResult = mockMvc.perform(get("/api/v1/csrf").cookie(session))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().path("XSRF-TOKEN", "/"))
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn();
        var csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/rooms")
                        .cookie(session, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"보안 경계 방","visibility":"PUBLIC","gameType":"LIAR"}
                            """))
                .andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedRequestUsesApiErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/00000000-0000-0000-0000-000000000001/snapshot")
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }
}
