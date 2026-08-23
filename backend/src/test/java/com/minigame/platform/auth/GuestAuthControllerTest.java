package com.minigame.platform.auth;

import com.minigame.platform.auth.adapter.in.web.CurrentActorController;
import com.minigame.platform.auth.adapter.in.web.GoogleOAuthSuccessHandler;
import com.minigame.platform.auth.adapter.in.web.GuestAuthController;
import com.minigame.platform.auth.adapter.in.web.SessionCookieAuthenticationFilter;
import com.minigame.platform.auth.adapter.out.persistence.MemberEntity;
import com.minigame.platform.auth.adapter.out.persistence.MemberRepository;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.auth.domain.ActorType;
import com.minigame.platform.shared.config.SecurityConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {GuestAuthController.class, CurrentActorController.class},
        properties = {
                "app.session.secret=test-key-that-is-at-least-32-bytes-long",
                "app.session.cookie-secure=false"
        }
)
@Import(SecurityConfig.class)
class GuestAuthControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionTokenService tokenService;

    @Autowired
    ObjectProvider<GoogleOAuthSuccessHandler> successHandler;

    @Test
    void createsGuestSessionWithFixedCookiePolicy() throws Exception {
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"  감자왕  \"}"))
                .andExpect(status().isCreated())
                .andExpect(cookie().httpOnly("APP_SESSION", true))
                .andExpect(cookie().secure("APP_SESSION", false))
                .andExpect(cookie().path("APP_SESSION", "/"))
                .andExpect(cookie().maxAge("APP_SESSION", 43_200))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andExpect(jsonPath("$.actorType").value("GUEST"))
                .andExpect(jsonPath("$.nickname").value("감자왕"));
    }

    @Test
    void marksTheApplicationSessionSecureOnHttps() throws Exception {
        mockMvc.perform(post("/api/v1/auth/guest")
                        .secure(true)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"감자왕\"}"))
                .andExpect(status().isCreated())
                .andExpect(cookie().secure("APP_SESSION", true));
    }

    @Test
    void restoresCurrentActorFromTheApplicationSessionCookie() throws Exception {
        var guestResult = mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"감자왕\"}"))
                .andReturn();
        var sessionCookie = guestResult.getResponse().getCookie("APP_SESSION");

        mockMvc.perform(get("/api/v1/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").isNotEmpty())
                .andExpect(jsonPath("$.actorType").value("GUEST"))
                .andExpect(jsonPath("$.nickname").value("감자왕"))
                .andExpect(jsonPath("$.memberId").doesNotExist());
    }

    @Test
    void rejectsMissingOrTamperedApplicationSessionCookie() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("APP_SESSION", "tampered")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidGuestNickname() throws Exception {
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"한\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startsWithoutGoogleCredentialsAndLeavesOauthRoutesAnonymous() throws Exception {
        assertThat(successHandler.getIfAvailable()).isNull();

        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/login/oauth2/code/google"))
                .andExpect(status().isNotFound());
    }

    @Test
    void doesNotRetainTheRawSessionTokenAsAuthenticationCredentials() throws Exception {
        var token = tokenService.issue(
                ActorPrincipal.guest(
                        new ActorId("guest-1"),
                        "감자왕"
                ),
                Duration.ofHours(12)
        );
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("APP_SESSION", token));

        try {
            new SessionCookieAuthenticationFilter(tokenService)
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

@WebMvcTest(
        controllers = {GuestAuthController.class, CurrentActorController.class},
        properties = {
                "app.session.secret=test-key-that-is-at-least-32-bytes-long",
                "app.session.cookie-secure=true",
                "spring.security.oauth2.client.registration.google.client-id=client-id",
                "spring.security.oauth2.client.registration.google.client-secret=client-secret"
        }
)
@Import(SecurityConfig.class)
class ConfiguredGoogleOAuthSuccessHandlerTest {
    @Autowired
    GoogleOAuthSuccessHandler successHandler;

    @Autowired
    SessionTokenService tokenService;

    @MockitoBean
    MemberRepository memberRepository;

    @Test
    void upsertsMemberAndIssuesSevenDayApplicationSession() throws Exception {
        when(memberRepository.findByGoogleSubject("google-subject")).thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var authentication = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(
                        java.util.List.of(),
                        Map.of(
                                "sub", "google-subject",
                                "email", "potato@example.com",
                                "name", "감자왕",
                                "picture", "https://example.com/avatar.png"
                        ),
                        "sub"
                ),
                java.util.List.of(),
                "google"
        );
        var response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/lobby");
        assertThat(response.getHeader("Set-Cookie"))
                .contains("APP_SESSION=", "Max-Age=604800", "Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("client-id", "client-secret");
        var cookie = response.getCookie("APP_SESSION");
        var principal = tokenService.verify(cookie.getValue());
        assertThat(principal.actorType()).isEqualTo(ActorType.MEMBER);
        assertThat(principal.nickname()).isEqualTo("감자왕");
        assertThat(principal.memberId()).isNotNull();

        var savedMember = ArgumentCaptor.forClass(MemberEntity.class);
        verify(memberRepository).save(savedMember.capture());
        assertThat(savedMember.getValue().getGoogleSubject()).isEqualTo("google-subject");
        assertThat(savedMember.getValue().getEmail()).isEqualTo("potato@example.com");
        assertThat(savedMember.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(savedMember.getValue().getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
