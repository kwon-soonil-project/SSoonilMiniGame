package com.minigame.platform.game.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnBean(GameSessionPort.class)
public class RunningGameSessionRecovery implements ApplicationListener<ApplicationReadyEvent> {
    private final GameSessionPort gameSessions;
    private final Clock clock;

    public RunningGameSessionRecovery(GameSessionPort gameSessions, Clock clock) {
        this.gameSessions = gameSessions;
        this.clock = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        gameSessions.interruptRunning(clock.instant());
    }
}
