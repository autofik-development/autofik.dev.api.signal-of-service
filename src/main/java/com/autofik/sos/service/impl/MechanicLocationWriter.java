package com.autofik.sos.service.impl;

import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The database half of a mechanic location ping.
 *
 * <p>Kept in its own bean so the broadcast half — a Google call, a Redis publish and a Kafka
 * fallback — runs with no Postgres connection checked out. Not transactional itself: a ping inside
 * the throttle window touches no table, and the one write is a single statement that carries its
 * own transaction (MechanicRepository#updateLocation).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationWriter {

  // Must stay 7 unless every stored current_h3_index is recomputed — see sos.h3.resolution.
  @Value("${sos.h3.resolution:7}")
  private int h3Resolution;

  private static final H3Core H3;

  static {
    try {
      H3 = H3Core.newInstance();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final String PG_SYNC_KEY_PREFIX = "mechanic:pg-sync:";

  @Value("${sos.location.pg-sync-seconds:10}")
  private int pgSyncSeconds;

  // A persisted ping this far from the previous one counts as the mechanic having moved.
  @Value("${sos.location.moved-threshold-meters:20}")
  private int movedThresholdMeters;

  private final MechanicRepository mechanicRepository;
  private final BidSosRepository bidSosRepository;
  private final StringRedisTemplate redisTemplate;

  /**
   * Persists the ping (subject to the throttle) and works out which SOS, if any, this position
   * should be broadcast on.
   *
   * @param sosId supplied by the caller when known. It is only trusted when the mechanic holds the
   *     accepted bid on that live SOS; otherwise it is ignored, so a mechanic cannot publish
   *     positions or trigger billed route lookups on someone else's ticket
   * @return the SOS to broadcast on, or null when the mechanic has no active job
   */
  public UUID persistAndResolveSos(UUID mechanicId, double lat, double lng, UUID sosId) {
    boolean persisted = writeLocation(mechanicId, lat, lng);

    if (sosId != null && bidSosRepository.isAssignedToActiveJob(mechanicId, sosId)) return sosId;

    // No sosId supplied (app restart, reconnect, older client). Resolve the active job here so
    // a mechanic mid-drive keeps broadcasting instead of the customer's map silently freezing.
    // Gated on `persisted` so idle mechanics — the large majority, pinging only to stay
    // discoverable — don't run this query on every ping just to learn they have no active job.
    if (!persisted) return null;

    return bidSosRepository
        .findActiveJob(mechanicId)
        .map(bid -> bid.getSosRequest().getSosId())
        .orElse(null);
  }

  // Postgres is the durable store. Redis throttle key limits writes to once per pgSyncSeconds.
  // When Redis is down the throttle key is unavailable, so every ping falls through to Postgres —
  // keeping the snapshot current during an outage.
  // Returns true when this ping actually wrote to Postgres, letting callers hang other
  // per-interval work off the same throttle.
  private boolean writeLocation(UUID mechanicId, double lat, double lng) {
    String key = PG_SYNC_KEY_PREFIX + mechanicId;
    boolean claimed = false;
    boolean shouldPersist;
    try {
      claimed =
          Boolean.TRUE.equals(
              redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(pgSyncSeconds)));
      shouldPersist = claimed;
    } catch (Exception e) {
      log.warn(
          "Redis unavailable for mechanic {}, writing to Postgres on every ping: {}",
          mechanicId,
          e.getMessage());
      shouldPersist = true;
    }

    // Inside the window the last persisted ping stands. Only a ping that was persisted can have
    // claimed the key, so the mechanic existed a moment ago and needs no second look.
    if (!shouldPersist) return false;

    int updated =
        mechanicRepository.updateLocation(
            mechanicId,
            lat,
            lng,
            H3.geoToH3Address(lat, lng, h3Resolution),
            movedThresholdMeters,
            LocalDateTime.now());
    if (updated == 0) {
      // An unknown mechanic must not be throttled into a 204: the next ping tries again.
      if (claimed) releaseThrottle(key);
      throw new EntityNotFoundException("Mechanic not found: " + mechanicId);
    }
    return true;
  }

  private void releaseThrottle(String key) {
    try {
      redisTemplate.delete(key);
    } catch (Exception e) {
      log.warn("Could not release {}: {}", key, e.getMessage());
    }
  }
}
