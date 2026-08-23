package com.minigame.platform.auth.adapter.in.web;

import com.minigame.platform.auth.domain.ActorPrincipal;
import com.minigame.platform.auth.domain.ActorType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public final class CurrentActorController {
    @GetMapping("/me")
    public CurrentActorResponse currentActor(@AuthenticationPrincipal ActorPrincipal principal) {
        return new CurrentActorResponse(
                principal.actorId().value(),
                principal.actorType(),
                principal.nickname(),
                principal.memberId()
        );
    }

    public record CurrentActorResponse(String actorId, ActorType actorType, String nickname, UUID memberId) {
    }
}
