package com.autofik.sos.config;

import com.autofik.sos.redis.MechanicLocationRedisListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Configuration class to set up Redis Pub/Sub message listeners. Specifically handles the
 * subscription to mechanic location update channels.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisLocationListenerConfig {

  private final RedisConnectionFactory redisConnectionFactory;
  private final MechanicLocationRedisListener mechanicLocationRedisListener;

  /**
   * Configures the Redis message listener container that asynchronously listens for incoming
   * messages on specified Redis channels.
   *
   * @return a configured {@link RedisMessageListenerContainer} instance
   */
  @Bean
  public RedisMessageListenerContainer redisLocationListenerContainer() {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();

    // Link the container to the Redis connection pool/factory
    container.setConnectionFactory(redisConnectionFactory);

    container.addMessageListener(
        mechanicLocationRedisListener,
        new PatternTopic(MechanicLocationRedisListener.CHANNEL_PREFIX + "*"));

    // Log Redis subscription errors; the container auto-reconnects after each failure.
    // Location delivery falls back to Kafka (MechanicLocationConsumer) while Redis is down.
    container.setErrorHandler(
        e ->
            log.error("Redis location listener error (Kafka fallback active): {}", e.getMessage()));

    return container;
  }
}
