package com.autofik.sos.config;

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

  @Bean
  public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    // Pool size of 2: enough for concurrent expiry firings without over-provisioning threads.
    // Increase if many SOS requests expire simultaneously under heavy load.
    scheduler.setPoolSize(2);
    // Named prefix makes expiry threads easy to identify in logs and thread dumps
    scheduler.setThreadNamePrefix("sos-expiry-");
    return scheduler;
  }
}
