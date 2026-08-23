package com.minigame.platform.shared.abuse;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

@Component
public final class ClientFingerprintService {
    private final byte[] key;
    private final Clock clock;
    private final Duration rotation;

    @Autowired
    public ClientFingerprintService(
            @Value("${app.abuse.ip-hash-secret:}") String configuredKey,
            @Value("${app.session.secret}") String sessionKey,
            Clock clock,
            @Value("${app.abuse.ip-hash-rotation:PT1H}") String rotation
    ) {
        this(
                (configuredKey == null || configuredKey.isBlank() ? sessionKey : configuredKey)
                        .getBytes(StandardCharsets.UTF_8),
                clock,
                Duration.parse(rotation)
        );
    }

    public ClientFingerprintService(byte[] key, Clock clock, Duration rotation) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("Abuse fingerprint secret must be at least 32 bytes");
        }
        if (rotation == null || rotation.isZero() || rotation.isNegative()) {
            throw new IllegalArgumentException("Fingerprint rotation must be positive");
        }
        this.key = key.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rotation = rotation;
    }

    public String fingerprint(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        var remoteAddress = Objects.requireNonNullElse(request.getRemoteAddr(), "unknown");
        var bucket = Math.floorDiv(clock.instant().toEpochMilli(), rotation.toMillis());
        var material = ("minigame-abuse-ip:" + bucket + ":" + remoteAddress).getBytes(StandardCharsets.UTF_8);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(material));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
