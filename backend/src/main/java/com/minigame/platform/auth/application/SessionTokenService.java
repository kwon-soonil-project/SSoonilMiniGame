package com.minigame.platform.auth.application;

import com.minigame.platform.auth.domain.ActorId;
import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.auth.domain.ActorType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

public final class SessionTokenService {
    private final byte[] key;
    private final Clock clock;

    private SessionTokenService(byte[] key, Clock clock) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("HMAC key must be at least 32 bytes");
        }
        this.key = key.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static SessionTokenService hmac(byte[] key, Clock clock) {
        return new SessionTokenService(key, clock);
    }

    public String issue(ActorPrincipal principal, Duration lifetime) {
        Objects.requireNonNull(principal, "principal");
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime");
        }

        var now = clock.instant();
        var claims = new JWTClaimsSet.Builder()
                .subject(principal.actorId().value())
                .claim("type", principal.actorType().name())
                .claim("nickname", ActorPrincipal.normalizeNickname(principal.nickname()))
                .claim("memberId", principal.memberId() == null ? null : principal.memberId().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign session token", exception);
        }
    }

    public ActorPrincipal verify(String token) {
        try {
            var jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(key))) {
                throw new InvalidSessionTokenException();
            }

            var claims = jwt.getJWTClaimsSet();
            var expiresAt = claims.getExpirationTime();
            if (claims.getIssueTime() == null
                    || expiresAt == null
                    || !clock.instant().isBefore(expiresAt.toInstant())) {
                throw new InvalidSessionTokenException();
            }

            var actorId = new ActorId(claims.getSubject());
            var actorType = ActorType.valueOf(claims.getStringClaim("type"));
            var nickname = claims.getStringClaim("nickname");
            var memberIdClaim = claims.getStringClaim("memberId");
            var memberId = memberIdClaim == null ? null : UUID.fromString(memberIdClaim);
            return new ActorPrincipal(actorId, actorType, nickname, memberId);
        } catch (InvalidSessionTokenException exception) {
            throw exception;
        } catch (JOSEException | ParseException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidSessionTokenException(exception);
        }
    }
}
