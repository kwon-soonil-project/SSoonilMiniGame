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
        var oauthUser = (OAuth2User) authentication.getPrincipal();
        var googleSubject = requiredAttribute(oauthUser, "sub");
        var email = requiredAttribute(oauthUser, "email");
        var nickname = safeNickname(oauthUser.getAttribute("name"));
        var avatarUrl = optionalAttribute(oauthUser, "picture");
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
        response.sendRedirect("/lobby");
    }

    private static String requiredAttribute(OAuth2User user, String name) {
        var value = optionalAttribute(user, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Google profile attribute: " + name);
        }
        return value;
    }

    private static String optionalAttribute(OAuth2User user, String name) {
        Object value = user.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private static String safeNickname(Object rawName) {
        if (rawName == null) {
            return "회원";
        }
        var withoutControls = rawName.toString().codePoints()
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
