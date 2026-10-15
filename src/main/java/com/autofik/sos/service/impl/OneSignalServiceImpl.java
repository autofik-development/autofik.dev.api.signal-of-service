package com.autofik.sos.service.impl;

import com.autofik.sos.dto.event.OneSignalPushFailedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.service.OneSignalService;
import com.onesignal.client.ApiException;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.model.LanguageStringMap;
import com.onesignal.client.model.Notification;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OneSignalServiceImpl implements OneSignalService {

  private final DefaultApi oneSignalApi;
  private final SosEventProducer sosEventProducer;

  @Value("${onesignal.app-id}")
  private String appId;

  // OneSignal accepts at most 2,000 aliases per notification request.
  private static final int MAX_ALIASES_PER_REQUEST = 2_000;

  // Set by Spring; stays NOOP when the class is built by hand (tests), which records nothing.
  @Autowired(required = false)
  ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

  static final String PUSH = "onesignal.push";

  /**
   * WORKFLOW: Sends a targeted real-time push notification alert directly to a specific user's
   * mobile device.
   */
  @Override
  public void send(
      UUID recipientId, String title, String body, Map<String, String> data, UUID idempotencyKey) {
    sendChunk(List.of(recipientId), title, body, data, idempotencyKey);
  }

  @Override
  public void sendToMany(
      List<UUID> recipientIds,
      String title,
      String body,
      Map<String, String> data,
      UUID idempotencyKey) {
    for (int from = 0; from < recipientIds.size(); from += MAX_ALIASES_PER_REQUEST) {
      List<UUID> chunk =
          recipientIds.subList(from, Math.min(from + MAX_ALIASES_PER_REQUEST, recipientIds.size()));
      sendChunk(chunk, title, body, data, chunkKey(idempotencyKey, from));
    }
  }

  // Each chunk is its own OneSignal notification, so it needs its own key: one shared key would
  // make OneSignal drop every chunk after the first. The first chunk keeps the original key.
  static UUID chunkKey(UUID idempotencyKey, int from) {
    if (idempotencyKey == null || from == 0) return idempotencyKey;
    return UUID.nameUUIDFromBytes((idempotencyKey + ":" + from).getBytes(StandardCharsets.UTF_8));
  }

  private void sendChunk(
      List<UUID> recipients,
      String title,
      String body,
      Map<String, String> data,
      UUID idempotencyKey) {
    try {
      // 1. INITIALIZATION: Instantiate a new OneSignal API notification container configuration
      // layout
      Notification notification = new Notification();
      notification.setAppId(appId);

      // 2. THE ALIAS STRATEGY: Target the recipient using our internal UUID as an external
      // reference lookup tag
      // Crucial Architecture Note: The "external_id" keyword must exactly match what your Flutter
      // mobile app
      // registers using the client-side SDK function: `OneSignal.login(userId)`
      notification.setIncludeAliases(
          Map.of("external_id", recipients.stream().map(UUID::toString).toList()));

      // 3. CHANNEL CONFIGURATION: Enforce that this transaction explicitly routes to the device's
      // native push alert center
      notification.setTargetChannel(Notification.TargetChannelEnum.PUSH);

      // 4. LOCALE CONTENT BINDING: Map localized language strings (defaulting to English
      // '.en(...)') for the UI presentation
      notification.setHeadings(
          new LanguageStringMap().en(title)); // Sets the prominent bold header text
      notification.setContents(
          new LanguageStringMap().en(body)); // Sets the descriptive lower-body notification message

      // 5. DEEP LINK PAYLOAD MAPPING: If deep-linking data arguments exist, inject them as an
      // invisible tracking dictionary
      if (data != null && !data.isEmpty()) {
        notification.setData(data);
      }
      if (idempotencyKey != null) {
        notification.setIdempotencyKey(idempotencyKey.toString());
      }

      // 6. SDK DELIVERY CALL: Dispatch the compiled request block synchronously over HTTP to
      // OneSignal's cloud servers. A span of its own, under the listener that took the event: it
      // carries how many recipients the request named, never who they are or what it said.
      Observation.createNotStarted(PUSH, observationRegistry)
          .highCardinalityKeyValue("onesignal.recipients", String.valueOf(recipients.size()))
          .observeChecked(() -> oneSignalApi.createNotification(notification));
    } catch (ApiException e) {
      // SDK-Specific Failure: Catch explicit HTTP errors returned directly by OneSignal's edge
      // gateways (e.g., HTTP 400 Bad Request)
      log.warn(
          "OneSignal push failed recipients={} status={}: {}",
          recipients.size() == 1 ? recipients.get(0) : recipients.size() + " ids",
          e.getCode(),
          e.getResponseBody());
      // Reported per recipient, so a failed batch reads the same as N failed single pushes.
      for (UUID recipientId : recipients) {
        sosEventProducer.publishOneSignalPushFailed(
            new OneSignalPushFailedEvent(
                recipientId, e.getCode(), e.getResponseBody(), LocalDateTime.now()));
      }
    } catch (Exception e) {
      // Generic System Failure: Protects the application thread against connection timeouts or
      // structural execution faults
      log.warn(
          "OneSignal push failed recipients={}: {}",
          recipients.size() == 1 ? recipients.get(0) : recipients.size() + " ids",
          e.getMessage());
      for (UUID recipientId : recipients) {
        sosEventProducer.publishOneSignalPushFailed(
            new OneSignalPushFailedEvent(recipientId, null, e.getMessage(), LocalDateTime.now()));
      }
    }
  }
}
