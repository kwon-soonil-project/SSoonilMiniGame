package com.minigame.platform.auth.adapter.in.web;

import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.auth.domain.ActorType;
import com.minigame.platform.shared.abuse.AbuseRateLimiter;
import com.minigame.platform.shared.abuse.ClientFingerprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/auth")
public final class GuestAuthController {
    private static final Duration GUEST_SESSION_LIFETIME = Duration.ofHours(12);

    private final SessionTokenService tokenService;
    private final boolean secureCookie;
    private final AbuseRateLimiter abuseLimiter;
    private final ClientFingerprintService clientFingerprints;

    public GuestAuthController(
            SessionTokenService tokenService,
            @Value("${app.session.cookie-secure:false}") boolean secureCookie,
            AbuseRateLimiter abuseLimiter,
            ClientFingerprintService clientFingerprints
    ) {
        this.tokenService = tokenService;
        this.secureCookie = secureCookie;
        this.abuseLimiter = abuseLimiter;
        this.clientFingerprints = clientFingerprints;
    }

    @PostMapping("/guest")
    public ResponseEntity<ActorResponse> createGuest(
            @RequestBody GuestRequest request,
            HttpServletRequest servletRequest
    ) {
        abuseLimiter.checkGuest(clientFingerprints.fingerprint(servletRequest));
        try {
            var principal = ActorPrincipal.guest(
                    new ActorId("guest:" + UUID.randomUUID()),
                    request.nickname()
            );
            var token = tokenService.issue(principal, GUEST_SESSION_LIFETIME);
            var cookie = applicationSessionCookie(
                    token,
                    GUEST_SESSION_LIFETIME,
                    secureCookie || servletRequest.isSecure()
            );
            return ResponseEntity.status(CREATED)
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(ActorResponse.from(principal));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid nickname");
        }
    }

    static ResponseCookie applicationSessionCookie(String token, Duration lifetime, boolean secure) {
        return ResponseCookie.from(SessionCookieAuthenticationFilter.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(lifetime)
                .build();
    }

    public record GuestRequest(String nickname) {
    }

    public record ActorResponse(String actorId, ActorType actorType, String nickname, UUID memberId) {
        static ActorResponse from(ActorPrincipal principal) {
            return new ActorResponse(
                    principal.actorId().value(),
                    principal.actorType(),
                    principal.nickname(),
                    principal.memberId()
            );
        }
    }
}
