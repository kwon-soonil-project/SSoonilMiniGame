package com.minigame.platform.auth.domain;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record ActorPrincipal(
        ActorId actorId,
        ActorType actorType,
        String nickname,
        UUID memberId
) implements Principal {
    public ActorPrincipal {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorType, "actorType");
        nickname = normalizeNickname(nickname);
        if (actorType == ActorType.GUEST && memberId != null) {
            throw new IllegalArgumentException("A guest cannot have a memberId");
        }
        if (actorType == ActorType.MEMBER && memberId == null) {
            throw new IllegalArgumentException("A member requires a memberId");
        }
    }

    public static ActorPrincipal guest(ActorId id, String nickname) {
        return new ActorPrincipal(id, ActorType.GUEST, nickname, null);
    }

    public static ActorPrincipal member(ActorId id, String nickname, UUID memberId) {
        return new ActorPrincipal(id, ActorType.MEMBER, nickname, memberId);
    }

    public static String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw new IllegalArgumentException("nickname");
        }
        var normalized = nickname.strip();
        var length = normalized.codePointCount(0, normalized.length());
        var hasControlCharacter = normalized.codePoints().anyMatch(Character::isISOControl);
        if (length < 2 || length > 12 || hasControlCharacter) {
            throw new IllegalArgumentException("nickname");
        }
        return normalized;
    }

    @Override
    public String getName() {
        return actorId.value();
    }
}
