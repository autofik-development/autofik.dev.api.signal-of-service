package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.service.OneSignalService;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer responsible for processing notification events. It listens to the designated SOS
 * notification topic and dispatches push notifications to target recipients via the OneSignal push
 * service.
 */
@Component
@Slf4j
public class NotificationConsumer {

  private final OneSignalService oneSignalService;

  // Each push is an HTTPS round trip to OneSignal (~570 ms measured from the load-test stack).
  // Sent one at a time on the listener thread, the three partition consumers drained ~5 pushes/s,
  // and a burst of bids or status changes left a 116k-record backlog. The pool keeps many pushes in
  // flight. It is bounded, and when it is full the listener thread sends the push itself
  // (CallerRuns): consumption slows, so the backlog waits in Kafka rather than in memory, and
  // nothing is dropped. A crash loses at most the pushes queued here; delivery was already
  // best-effort (a failed push is logged and reported, never retried).
  private final Executor pushExecutor;
  private final ThreadPoolTaskExecutor ownedPool;

  @Autowired
  public NotificationConsumer(
      OneSignalService oneSignalService,
      @Value("${onesignal.push-threads:32}") int pushThreads,
      @Value("${onesignal.push-queue-capacity:1000}") int queueCapacity) {
    this.oneSignalService = oneSignalService;
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setThreadNamePrefix("onesignal-push-");
    pool.setCorePoolSize(pushThreads);
    pool.setMaxPoolSize(pushThreads);
    pool.setQueueCapacity(queueCapacity);
    pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    // On shutdown, give queued pushes a moment to go out instead of dropping them.
    pool.setWaitForTasksToCompleteOnShutdown(true);
    pool.setAwaitTerminationSeconds(10);
    pool.initialize();
    this.ownedPool = pool;
    this.pushExecutor = pool;
  }

  /** Tests pass {@code Runnable::run} to send on the calling thread. */
  public NotificationConsumer(OneSignalService oneSignalService, Executor pushExecutor) {
    this.oneSignalService = oneSignalService;
    this.pushExecutor = pushExecutor;
    this.ownedPool = null;
  }

  @PreDestroy
  public void shutdown() {
    if (ownedPool != null) ownedPool.shutdown();
  }

  /**
   * Consumes incoming notification events from Kafka. Filters out broadcast events and routes
   * targeted notifications to individual users.
   *
   * @param event the incoming payload containing notification details
   */
  @KafkaListener(
      topics = KafkaConfig.TOPIC_SOS_NOTIFICATION,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "notificationListenerContainerFactory")
  public void consume(SosNotificationEvent event) {
    // A null record (tombstone, or a payload that failed to deserialize) has nothing to relay.
    // Throwing here would only make the container retry the same record.
    if (event == null) {
      log.warn("Ignoring null notification record");
      return;
    }
    // One push, same content, many recipients (e.g. every runner-up when a bid is accepted).
    if (event.recipientIds() != null && !event.recipientIds().isEmpty()) {
      Map<String, String> data = Map.of("type", event.type(), "sosId", event.sosId().toString());
      pushExecutor.execute(
          () -> {
            try {
              oneSignalService.sendToMany(event.recipientIds(), event.title(), event.body(), data);
            } catch (Exception e) {
              log.warn(
                  "Notification delivery failed type={} recipients={}: {}",
                  event.type(),
                  event.recipientIds().size(),
                  e.getMessage());
            }
          });
      return;
    }
    // SOS_CREATED fan-out has no specific recipient — already handled by WebSocket fan-out
    // We safely bypass execution here to avoid sending individual duplicate push alerts.
    if (event.recipientId() == null) return;

    pushExecutor.execute(
        () -> {
          try {
            // Forward targeted notification payloads directly to the OneSignal external push
            // service
            oneSignalService.send(
                event.recipientId(),
                event.title(),
                event.body(),
                Map.of("type", event.type(), "sosId", event.sosId().toString()));
          } catch (Exception e) {
            // A third-party failure must not kill the push thread or reach the Kafka listener
            log.warn(
                "Notification delivery failed type={} recipient={}: {}",
                event.type(),
                event.recipientId(),
                e.getMessage());
          }
        });
  }
}
