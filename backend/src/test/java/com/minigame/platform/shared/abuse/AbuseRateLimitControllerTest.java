package com.minigame.platform.shared.abuse;

import com.minigame.platform.auth.adapter.in.web.GuestAuthController;
import com.minigame.platform.auth.adapter.in.web.CsrfController;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.room.adapter.in.web.RoomController;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import com.minigame.platform.room.application.ChatPolicy;
import com.minigame.platform.room.application.RoomApplicationService;
import com.minigame.platform.shared.config.SecurityConfig;
import com.minigame.platform.shared.error.GlobalExceptionHandler;
import com.minigame.platform.shared.realtime.RoomEventPublisher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = GuestAuthController.class,
        properties = {
                "app.session.secret=test-key-that-is-at-least-32-bytes-long",
                "app.abuse.ip-hash-secret=test-ip-hash-key-at-least-32-bytes",
                "app.abuse.guest.capacity=1",
                "app.abuse.guest.window=PT1M"
        }
)
@Import({SecurityConfig.class, AbuseRateLimiter.class, ClientFingerprintService.class, GlobalExceptionHandler.class})
class GuestAbuseRateLimitControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void returnsStable429AndRetryAfterWhenOneClientCreatesGuestsTooFast() throws Exception {
        mockMvc.perform(post("/api/v1/auth/guest")
                        .with(request -> { request.setRemoteAddr("203.0.113.21"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"첫감자\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/guest")
                        .with(request -> { request.setRemoteAddr("203.0.113.21"); return request; })
                        .header("X-Request-Id", "00000000-0000-0000-0000-000000009201")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"둘감자\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.requestId").value("00000000-0000-0000-0000-000000009201"));
    }
}

@WebMvcTest(
        controllers = {RoomController.class, CsrfController.class},
        properties = {
                "app.session.secret=test-key-that-is-at-least-32-bytes-long",
                "app.abuse.ip-hash-secret=test-ip-hash-key-at-least-32-bytes",
                "app.abuse.password.capacity=1",
                "app.abuse.password.window=PT1M"
        }
)
@Import({
        SecurityConfig.class, AbuseRateLimiter.class, ClientFingerprintService.class,
        ChatPolicy.class, RoomApplicationService.class, InMemoryActiveRoomRepository.class,
        GlobalExceptionHandler.class
})
class RoomPasswordAbuseRateLimitControllerTest {
    private static final ActorPrincipal HOST = ActorPrincipal.guest(new ActorId("limit-host"), "방장감자");
    private static final ActorPrincipal ATTACKER = ActorPrincipal.guest(new ActorId("limit-attacker"), "공격감자");

    @Autowired MockMvc mockMvc;
    @Autowired SessionTokenService tokenService;
    @MockitoBean RoomEventPublisher eventPublisher;

    @Test
    void rateLimitsPasswordVerificationBeforeAnotherExpensiveAttempt() throws Exception {
        var create = postWithCsrf("/api/v1/rooms", HOST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"보호방\",\"visibility\":\"PUBLIC\",\"password\":\"1234\",\"gameType\":\"LIAR\"}");
        var result = mockMvc.perform(create).andExpect(status().isCreated()).andReturn();
        var code = result.getResponse().getContentAsString().replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(postWithCsrf("/api/v1/rooms/" + code + "/join", ATTACKER)
                        .with(request -> { request.setRemoteAddr("203.0.113.22"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(postWithCsrf("/api/v1/rooms/" + code + "/join", ATTACKER)
                        .with(request -> { request.setRemoteAddr("203.0.113.22"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithCsrf(
            String path, ActorPrincipal actor
    ) throws Exception {
        var session = new Cookie("APP_SESSION", tokenService.issue(actor, Duration.ofHours(1)));
        var csrf = mockMvc.perform(get("/api/v1/csrf").cookie(session)).andReturn().getResponse()
                .getCookie("XSRF-TOKEN");
        return post(path).cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }
}
