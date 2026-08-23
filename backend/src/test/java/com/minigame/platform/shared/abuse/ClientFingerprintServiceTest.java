package com.minigame.platform.shared.abuse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClientFingerprintServiceTest {
    @Test
    void hashesTheFrameworkNormalizedRemoteAddressWithoutTrustingRawForwardedHeaders() {
        var service = new ClientFingerprintService(
                "0123456789abcdef0123456789abcdef".getBytes(),
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(1)
        );
        var first = request("203.0.113.10", "198.51.100.1");
        var second = request("203.0.113.10", "192.0.2.1");

        var fingerprint = service.fingerprint(first);

        assertThat(fingerprint).isEqualTo(service.fingerprint(second));
        assertThat(fingerprint).doesNotContain("203.0.113.10", "198.51.100.1");
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
