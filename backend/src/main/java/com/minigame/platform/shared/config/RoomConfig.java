package com.minigame.platform.shared.config;

import com.minigame.platform.game.adapter.out.scheduling.SpringGameScheduler;
import com.minigame.platform.game.application.GameSchedulePort;
import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RoomConfig {
    @Bean
    ActiveRoomRepository activeRoomRepository() {
        return new InMemoryActiveRoomRepository();
    }

    @Bean
    ThreadPoolTaskScheduler roomDisconnectTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("room-disconnect-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean(name = "gameDeadlineTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler gameDeadlineTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("game-deadline-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    GameSchedulePort gameSchedulePort(
            @Qualifier("gameDeadlineTaskScheduler") ThreadPoolTaskScheduler scheduler,
            Clock applicationClock
    ) {
        return new SpringGameScheduler(scheduler, applicationClock);
    }
}
