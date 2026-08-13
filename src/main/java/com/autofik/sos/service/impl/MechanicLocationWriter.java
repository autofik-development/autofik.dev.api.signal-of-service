package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * The transactional half of a mechanic location ping.
 *
 * Kept in its own bean so the broadcast half — a Google call, a Redis publish and a Kafka
 * fallback — runs with no Postgres connection checked out. Calling this from within
 * MechanicServiceImpl would not work: self-invocation bypasses the proxy, so the boundary
 * has to sit on a different bean to take effect.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationWriter {

    private static final int H3_RESOLUTION = 7;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final H3Core H3;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String PG_SYNC_KEY_PREFIX = "mechanic:pg-sync:";
    private static final Duration PG_SYNC_INTERVAL = Duration.ofSeconds(10);

    private final MechanicRepository mechanicRepository;
    private final BidSosRepository bidSosRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Persists the ping (subject to the throttle) and works out which SOS, if any, this
     * position should be broadcast on.
     *
     * @param sosId supplied by the caller when known, which skips the active-bid lookup entirely
     * @return the SOS to broadcast on, or null when the mechanic has no active job
     */
    @Transactional
    public UUID persistAndResolveSos(UUID mechanicId, double lat, double lng, UUID sosId) {
        boolean persisted = writeLocation(mechanicId, lat, lng);

        if (sosId != null) return sosId;

        // No sosId supplied (app restart, reconnect, older client). Resolve the active job here so
        // a mechanic mid-drive keeps broadcasting instead of the customer's map silently freezing.
        // Gated on `persisted` so idle mechanics — the large majority, pinging only to stay
        // discoverable — don't run this query on every ping just to learn they have no active job.
        if (!persisted) return null;

        return bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .map(bid -> bid.getSosRequest().getSosId())
                .orElse(null);
    }

    // Postgres is the durable store. Redis throttle key limits writes to once per PG_SYNC_INTERVAL.
    // When Redis is down the throttle key is unavailable, so every ping falls through to Postgres —
    // keeping the snapshot current during an outage.
    // Returns true when this ping actually wrote to Postgres, letting callers hang other
    // per-interval work off the same throttle.
    private boolean writeLocation(UUID mechanicId, double lat, double lng) {
        boolean shouldPersist;
        try {
            Boolean throttle = redisTemplate.opsForValue()
                    .setIfAbsent(PG_SYNC_KEY_PREFIX + mechanicId, "1", PG_SYNC_INTERVAL);
            shouldPersist = Boolean.TRUE.equals(throttle);
        } catch (Exception e) {
            log.warn("Redis unavailable for mechanic {}, writing to Postgres on every ping: {}", mechanicId, e.getMessage());
            shouldPersist = true;
        }

        if (shouldPersist) {
            Mechanic mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
            mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
            mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
            mechanicRepository.save(mechanic);
        } else if (!mechanicRepository.existsById(mechanicId)) {
            throw new EntityNotFoundException("Mechanic not found: " + mechanicId);
        }
        return shouldPersist;
    }
}
