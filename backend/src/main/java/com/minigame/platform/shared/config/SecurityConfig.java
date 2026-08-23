package com.minigame.platform.shared.config;

import com.minigame.platform.auth.adapter.in.web.GoogleOAuthSuccessHandler;
import com.minigame.platform.auth.adapter.in.web.SessionCookieAuthenticationFilter;
import com.minigame.platform.auth.adapter.out.persistence.MemberRepository;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.shared.error.ApiErrorWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    SessionTokenService sessionTokenService(
            @Value("${app.session.secret}") String secret,
            Clock applicationClock
    ) {
        return SessionTokenService.hmac(secret.getBytes(StandardCharsets.UTF_8), applicationClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "spring.security.oauth2.client.registration.google",
            name = {"client-id", "client-secret"}
    )
    GoogleOAuthSuccessHandler googleOAuthSuccessHandler(
            MemberRepository memberRepository,
            SessionTokenService tokenService,
            Clock applicationClock,
            @Value("${app.session.cookie-secure:false}") boolean secureCookie
    ) {
        return new GoogleOAuthSuccessHandler(memberRepository, tokenService, applicationClock, secureCookie);
    }

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            SessionTokenService tokenService,
            ObjectProvider<GoogleOAuthSuccessHandler> googleSuccessHandler,
            JsonMapper jsonMapper
    ) throws Exception {
        var sessionFilter = new SessionCookieAuthenticationFilter(tokenService);
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/api/v1/auth/guest")
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/assets/**",
                                "/static/**",
                                "/api/v1/auth/guest",
                                "/api/v1/csrf",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/error",
                                "/oauth2/**",
                                "/login/oauth2/**"
                        ).permitAll()
                        .anyRequest().access((authentication, context) -> new AuthorizationDecision(
                                authentication.get().isAuthenticated()
                                        && authentication.get().getPrincipal()
                                        instanceof ActorPrincipal
                        ))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> ApiErrorWriter.write(
                                jsonMapper,
                                request,
                                response,
                                401,
                                "UNAUTHORIZED",
                                "로그인이 필요합니다."
                        ))
                        .accessDeniedHandler((request, response, exception) -> ApiErrorWriter.write(
                                jsonMapper,
                                request,
                                response,
                                403,
                                "FORBIDDEN",
                                "요청 권한이 없습니다."
                        ))
                )
                .addFilterBefore(sessionFilter, AnonymousAuthenticationFilter.class);

        var successHandler = googleSuccessHandler.getIfAvailable();
        if (successHandler != null) {
            http.oauth2Login(oauth -> oauth.successHandler(successHandler));
        }
        return http.build();
    }
}
