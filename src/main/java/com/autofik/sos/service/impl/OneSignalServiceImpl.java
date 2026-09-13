package com.autofik.sos.service.impl;

import com.autofik.sos.dto.event.OneSignalPushFailedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.service.OneSignalService;
import com.onesignal.client.ApiException;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.model.LanguageStringMap;
import com.onesignal.client.model.Notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  /**
   * WORKFLOW: Sends a targeted real-time push notification alert directly to a specific user's
   * mobile device.
   */
  @Override
  public void send(UUID recipientId, String title, String body, Map<String, String> data) {
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
      notification.setIncludeAliases(Map.of("external_id", List.of(recipientId.toString())));

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

      // 6. SDK DELIVERY CALL: Dispatch the compiled request block synchronously over HTTP to
      // OneSignal's cloud servers
      oneSignalApi.createNotification(notification);
    } catch (ApiException e) {
      // SDK-Specific Failure: Catch explicit HTTP errors returned directly by OneSignal's edge
      // gateways (e.g., HTTP 400 Bad Request)
      log.warn(
          "OneSignal push failed recipient={} status={}: {}",
          recipientId,
          e.getCode(),
          e.getResponseBody());
      sosEventProducer.publishOneSignalPushFailed(
              new OneSignalPushFailedEvent(
                      recipientId,
                      e.getCode(),
                      e.getResponseBody(),
              LocalDateTime.now())
      );
    } catch (Exception e) {
      // Generic System Failure: Protects the application thread against connection timeouts or
      // structural execution faults
      log.warn("OneSignal push failed recipient={}: {}", recipientId, e.getMessage());
      sosEventProducer.publishOneSignalPushFailed(
              new OneSignalPushFailedEvent(
                      recipientId,
                      null,
                      e.getMessage(),
                      LocalDateTime.now()
              )
      );
    }
  }

}
