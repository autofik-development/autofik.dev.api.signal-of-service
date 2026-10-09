package com.autofik.sos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Configures the TaskScheduler bean used by SosServiceImpl to schedule per-SOS bidding expiry
 * tasks. Each new SOS request schedules one future task to auto-expire it if no bid is accepted in
 * time.
 */
@Configuration
@EnableScheduling // Required to activate Spring's @Scheduled and TaskScheduler support
public class SchedulerConfig {

  // Every per-ticket expiry timer and every @Scheduled sweep (expiry, completion, no-show) runs on
  // this pool. A mass expiry makes the sweep work through hundreds of tickets one after another.
  @Value("${sos.scheduler.pool-size:4}")
  private int poolSize;

  @Bean
  public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(poolSize);
    // Named prefix makes expiry threads easy to identify in logs and thread dumps
    scheduler.setThreadNamePrefix("sos-expiry-");
    return scheduler;
  }

  @Bean
  public TaskScheduler heartbeatTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stomp-heartbeat");
    return scheduler;
  }
}
