package com.minigame.platform.auth;

import com.minigame.platform.auth.application.InvalidSessionTokenException;
import com.minigame.platform.auth.application.SessionTokenService;
import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTokenServiceTest {
    private static final byte[] KEY = "test-key-that-is-at-least-32-bytes-long"
            .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    private final SessionTokenService service = SessionTokenService.hmac(
            KEY,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void restoresIssuedGuestPrincipal() {
        var actor = ActorPrincipal.guest(new ActorId("guest-1"), "감자왕");

        assertThat(service.verify(service.issue(actor, Duration.ofHours(12)))).isEqualTo(actor);
    }

    @Test
    void rejectsModifiedToken() {
        var token = service.issue(
                ActorPrincipal.guest(new ActorId("guest-1"), "감자왕"),
                Duration.ofHours(12)
        );

        assertThatThrownBy(() -> service.verify(token + "x"))
                .isInstanceOf(InvalidSessionTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        var token = service.issue(
                ActorPrincipal.guest(new ActorId("guest-1"), "감자왕"),
                Duration.ofSeconds(1)
        );
        var afterExpiry = SessionTokenService.hmac(
                KEY,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> afterExpiry.verify(token))
                .isInstanceOf(InvalidSessionTokenException.class);
    }

    @Test
    void trimsNicknameBeforeItIsSigned() {
        var actor = ActorPrincipal.guest(new ActorId("guest-1"), "  감자왕  ");

        assertThat(service.verify(service.issue(actor, Duration.ofHours(12))).nickname())
                .isEqualTo("감자왕");
    }

    @Test
    void rejectsNicknameOutsideLengthAndControlCharacterRules() {
        assertThatThrownBy(() -> ActorPrincipal.guest(new ActorId("guest-1"), "한"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActorPrincipal.guest(new ActorId("guest-1"), "abcdefghijklmn"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActorPrincipal.guest(new ActorId("guest-1"), "감자\n왕"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
