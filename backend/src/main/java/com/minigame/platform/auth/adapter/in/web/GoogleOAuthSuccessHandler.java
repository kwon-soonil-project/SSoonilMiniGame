package com.minigame.platform.auth.adapter.in.web;

import com.minigame.platform.auth.adapter.out.persistence.MemberEntity;
import com.minigame.platform.auth.adapter.out.persistence.MemberRepository;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public final class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {
    private static final Duration MEMBER_SESSION_LIFETIME = Duration.ofDays(7);

    private final MemberRepository memberRepository;
    private final SessionTokenService tokenService;
    private final Clock clock;
    private final boolean secureCookie;

    public GoogleOAuthSuccessHandler(
            MemberRepository memberRepository,
            SessionTokenService tokenService,
            Clock clock,
            boolean secureCookie
    ) {
        this.memberRepository = memberRepository;
        this.tokenService = tokenService;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        var googleAuthentication = requireGoogleAuthentication(authentication);
        var oauthUser = googleAuthentication.getPrincipal();
        var googleSubject = requiredStringAttribute(oauthUser, "sub");
        var email = requiredStringAttribute(oauthUser, "email");
        var nickname = safeNickname(optionalStringAttribute(oauthUser, "name"));
        var avatarUrl = optionalStringAttribute(oauthUser, "picture");
        var now = clock.instant();

        var member = memberRepository.findByGoogleSubject(googleSubject)
                .map(existing -> {
                    existing.recordGoogleLogin(email, nickname, avatarUrl, now);
                    return existing;
                })
                .orElseGet(() -> MemberEntity.create(
                        UUID.randomUUID(),
                        googleSubject,
                        email,
                        nickname,
                        avatarUrl,
                        now
                ));
        member = memberRepository.save(member);

        var principal = ActorPrincipal.member(
                new ActorId(member.getId().toString()),
                member.getNickname(),
                member.getId()
        );
        var token = tokenService.issue(principal, MEMBER_SESSION_LIFETIME);
        var cookie = GuestAuthController.applicationSessionCookie(
                token,
                MEMBER_SESSION_LIFETIME,
                secureCookie || request.isSecure()
        );
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        var oauthSession = request.getSession(false);
        if (oauthSession != null) {
            oauthSession.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.sendRedirect("/lobby");
    }

    private static OAuth2AuthenticationToken requireGoogleAuthentication(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthAuthentication)
                || !"google".equals(oauthAuthentication.getAuthorizedClientRegistrationId())) {
            throw invalidGoogleIdentity("Unexpected OAuth registration");
        }
        return oauthAuthentication;
    }

    private static String requiredStringAttribute(OAuth2User user, String name) {
        Object value = user.getAttribute(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw invalidGoogleIdentity("Invalid Google profile attribute: " + name);
        }
        return stringValue.strip();
    }

    private static String optionalStringAttribute(OAuth2User user, String name) {
        Object value = user.getAttribute(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw invalidGoogleIdentity("Invalid Google profile attribute: " + name);
        }
        var normalized = stringValue.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static OAuth2AuthenticationException invalidGoogleIdentity(String description) {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_user_info_response"),
                description
        );
    }

    private static String safeNickname(String rawName) {
        if (rawName == null) {
            return "회원";
        }
        var withoutControls = rawName.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        if (withoutControls.codePointCount(0, withoutControls.length()) > 12) {
            var end = withoutControls.offsetByCodePoints(0, 12);
            withoutControls = withoutControls.substring(0, end);
        }
        if (withoutControls.codePointCount(0, withoutControls.length()) < 2) {
            return "회원";
        }
        return ActorPrincipal.normalizeNickname(withoutControls);
    }
}
