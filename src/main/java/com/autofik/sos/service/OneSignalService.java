package com.autofik.sos.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service Interface managing integration with OneSignal (Third-party Push Notification Provider).
 * Handles sending system alerts, pop-up messages, and background data payloads to mobile devices.
 */
public interface OneSignalService {
  /**
   * Use Case: Deliver a push notification to a specific user or mechanic's mobile device. * @param
   * recipientId The unique ID of the target User or Mechanic. The implementation will map this
   * internal ID to their external OneSignal device token (Player ID).
   *
   * @param title The bold heading text of the notification (e.g., "Mechanic En Route!").
   * @param body The main text description (e.g., "Bong Reaksa has accepted your request and arrives
   *     in 10 mins.").
   * @param data A key-value map of hidden background data payloads (e.g., {"sosId":
   *     "123e4567-e89b..."}). When the user taps the notification, the mobile app reads this map to
   *     jump directly to the correct emergency tracking screen.
   */
  void send(UUID recipientId, String title, String body, Map<String, String> data);

  /** One push with the same content to many recipients, in as few OneSignal requests as allowed. */
  void sendToMany(List<UUID> recipientIds, String title, String body, Map<String, String> data);
}
