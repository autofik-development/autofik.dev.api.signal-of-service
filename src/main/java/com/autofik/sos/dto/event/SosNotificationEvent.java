package com.autofik.sos.dto.event;

import com.autofik.sos.dto.external.EtaInfo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Notification payload. {@code photoUrls} is only populated for the mechanic fan-out, where the
 * photos decide whether a mechanic bids at all; it stays null on targeted push notifications.
 * {@code estimate} is likewise fan-out only: that mechanic's rough distance/time to the SOS. {@code
 * recipientIds} makes one event a push to many people with the same content (all runners-up when a
 * bid is accepted): one Kafka record and one OneSignal request instead of one per person. {@code
 * recipientId} is null on those. Records written before the field existed read it as null.
 */
public record SosNotificationEvent(
    String type,
    UUID recipientId,
    String recipientRole,
    UUID sosId,
    String title,
    String body,
    List<String> photoUrls,
    EtaInfo estimate,
    List<UUID> recipientIds,
    Customer customer,
    String problemType,
    OffsetDateTime biddingExpiresAt,
    String locationAddress) {

  /** Who raised the SOS and with what car; mechanic fan-out only, null on every other frame. */
  public record Customer(
      String name,
      String vehicleBrand,
      String vehicleModel,
      String vehicleYear,
      String licensePlate,
      String fuelType) {}

  /**
   * {@code problemType} (machine-readable, for the icon), {@code biddingExpiresAt} (+07:00, the app
   * counts down to it itself) and {@code locationAddress} (where the vehicle is). Null on every
   * frame that is not an offer.
   */
  public SosNotificationEvent(
      String type,
      UUID recipientId,
      String recipientRole,
      UUID sosId,
      String title,
      String body,
      List<String> photoUrls,
      EtaInfo estimate,
      List<UUID> recipientIds,
      Customer customer) {
    this(
        type,
        recipientId,
        recipientRole,
        sosId,
        title,
        body,
        photoUrls,
        estimate,
        recipientIds,
        customer,
        null,
        null,
        null);
  }

  public SosNotificationEvent(
      String type,
      UUID recipientId,
      String recipientRole,
      UUID sosId,
      String title,
      String body,
      List<String> photoUrls,
      EtaInfo estimate,
      List<UUID> recipientIds) {
    this(
        type,
        recipientId,
        recipientRole,
        sosId,
        title,
        body,
        photoUrls,
        estimate,
        recipientIds,
        null);
  }

  public SosNotificationEvent(
      String type,
      UUID recipientId,
      String recipientRole,
      UUID sosId,
      String title,
      String body,
      List<String> photoUrls,
      EtaInfo estimate) {
    this(type, recipientId, recipientRole, sosId, title, body, photoUrls, estimate, null, null);
  }

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
