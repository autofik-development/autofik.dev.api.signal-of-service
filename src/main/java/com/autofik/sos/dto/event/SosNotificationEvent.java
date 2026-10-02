package com.autofik.sos.dto.event;

import com.autofik.sos.dto.external.EtaInfo;
import java.util.List;
import java.util.UUID;

/**
 * Notification payload. {@code photoUrls} is only populated for the mechanic fan-out, where the
 * photos decide whether a mechanic bids at all; it stays null on targeted push notifications.
 * {@code estimate} is likewise fan-out only: that mechanic's rough distance/time to the SOS.
 */
public record SosNotificationEvent(
    String type,
    UUID recipientId,
    String recipientRole,
    UUID sosId,
    String title,
    String body,
    List<String> photoUrls,
    EtaInfo estimate) {

  public SosNotificationEvent(
      String type, UUID recipientId, String recipientRole, UUID sosId, String title, String body) {
    this(type, recipientId, recipientRole, sosId, title, body, null, null);
  }

  public SosNotificationEvent(
      String type,
      UUID recipientId,
      String recipientRole,
      UUID sosId,
      String title,
      String body,
      List<String> photoUrls) {
    this(type, recipientId, recipientRole, sosId, title, body, photoUrls, null);
  }
}
