package com.minigame.platform.auth.adapter.in.web;

import com.minigame.platform.auth.application.InvalidSessionTokenException;
import com.minigame.platform.auth.application.SessionTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

public final class SessionCookieAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "APP_SESSION";

    private final SessionTokenService tokenService;

    public SessionCookieAuthenticationFilter(SessionTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            findSessionCookie(request).ifPresent(token -> authenticate(token));
        }
        filterChain.doFilter(request, response);
    }

    private java.util.Optional<String> findSessionCookie(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void authenticate(String token) {
        try {
            var principal = tokenService.verify(token);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    java.util.List.of()
            );
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (InvalidSessionTokenException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
