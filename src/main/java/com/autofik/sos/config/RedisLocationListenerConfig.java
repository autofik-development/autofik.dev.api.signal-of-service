package com.autofik.sos.config;

import com.autofik.sos.redis.MechanicLocationRedisListener;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@RequiredArgsConstructor
public class RedisLocationListenerConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final MechanicLocationRedisListener mechanicLocationRedisListener;

    @Bean
    public RedisMessageListenerContainer redisLocationListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(
                mechanicLocationRedisListener,
                new PatternTopic(MechanicLocationRedisListener.CHANNEL_PREFIX + "*")
        );
        return container;
    }
}
