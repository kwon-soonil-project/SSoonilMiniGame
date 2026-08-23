package com.minigame.platform.shared.config;

import com.minigame.platform.room.adapter.out.memory.InMemoryActiveRoomRepository;
import com.minigame.platform.room.application.ActiveRoomRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RoomConfig {
    @Bean
    ActiveRoomRepository activeRoomRepository() {
        return new InMemoryActiveRoomRepository();
    }
}
