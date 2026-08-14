package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.dto.external.EtaSnapshot;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.util.GeoDistance;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteEtaServiceImpl implements RouteEtaService {

    // Whoever wins setIfAbsent within the window pays for the Google call; every other
    // ping in that window interpolates against the cached snapshot for free.
    private static final String ETA_LOCK_PREFIX = "sos:eta:lock:";
    private static final String ETA_CACHE_PREFIX = "sos:eta:";
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);
    // Outlives REFRESH_INTERVAL so the snapshot is always available to interpolate against;
    // bounded so an abandoned job cannot pin the entry forever.
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    // Used only when the Maps API yields nothing (no key configured, quota exhausted,
    // or no drivable route). Rough by construction — flagged as estimated in the response.
    private static final double FALLBACK_SPEED_KMH = 25.0;
    private static final double ROAD_WINDING_FACTOR = 1.35;

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    // The only two states that mean "a mechanic is currently driving toward the customer".
    private static final Set<SosStatus> EN_ROUTE = EnumSet.of(SosStatus.ACCEPTED, SosStatus.ON_THE_WAY);

    private final SosRequestRepository sosRequestRepository;
    private final GoogleMapsService googleMapsService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // L2 fallback for both the gate and the snapshot, used only while Redis is unreachable.
    // Per-instance rather than shared, so an outage costs one Google call per window per
    // instance instead of one per ping — bounded degradation rather than none.
    private final Cache<UUID, Boolean> localGate = Caffeine.newBuilder()
            .expireAfterWrite(REFRESH_INTERVAL)
            .maximumSize(10_000)
            .build();
    private final Cache<UUID, EtaSnapshot> localSnapshots = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    // Deliberately not @Transactional: refresh() makes a blocking Google call, and wrapping this
    // would hold a Postgres connection for its duration on the hottest path in the service. The
    // single findById inside refresh() runs in its own Spring Data transaction, and nothing read
    // from the entity is lazy, so no session is needed past that call.
    @Override
    public EtaInfo currentEta(UUID sosId, double mechanicLat, double mechanicLng) {
        if (sosId == null) return null;

        // Common path: still inside the refresh window, so interpolate against the last
        // Google result. No database read and no API cost.
        if (!shouldRefresh(sosId)) {
            EtaSnapshot cached = readSnapshot(sosId);
            if (cached != null) return interpolate(cached, mechanicLat, mechanicLng);
            // Cache expired ahead of the gate — fall through and refresh anyway.
        }

        return refresh(sosId, mechanicLat, mechanicLng);
    }

    @Override
    public void clear(UUID sosId) {
        if (sosId == null) return;
        localGate.invalidate(sosId);
        localSnapshots.invalidate(sosId);
        try {
            redisTemplate.delete(ETA_CACHE_PREFIX + sosId);
            redisTemplate.delete(ETA_LOCK_PREFIX + sosId);
        } catch (Exception e) {
            log.warn("Failed clearing ETA cache for SOS {}: {}", sosId, e.getMessage());
        }
    }

    // True when this ping is the one that should pay for a Google call.
    private boolean shouldRefresh(UUID sosId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(ETA_LOCK_PREFIX + sosId, "1", REFRESH_INTERVAL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis unavailable for ETA throttle on SOS {}, using in-process gate: {}",
                    sosId, e.getMessage());
            if (localGate.getIfPresent(sosId) != null) return false;
            localGate.put(sosId, Boolean.TRUE);
            return true;
        }
    }

    private EtaInfo refresh(UUID sosId, double lat, double lng) {
        SosRequest sos = sosRequestRepository.findById(sosId).orElse(null);
        // Nothing to count down once the mechanic has arrived or the job has ended.
        if (sos == null || !EN_ROUTE.contains(sos.getStatus())) {
            clear(sosId);
            return null;
        }

        Point destination = sos.getLocation();
        Point origin = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
        DistanceInfo info = googleMapsService.getDistanceInfo(origin, destination);

        EtaSnapshot snapshot = (info != null)
                ? new EtaSnapshot(info.distanceMeters(), info.durationSeconds(),
                        lat, lng, destination.getY(), destination.getX(), false)
                : straightLineSnapshot(lat, lng, destination);

        writeSnapshot(sosId, snapshot);
        return toInfo(snapshot.distanceMeters(), snapshot.durationSeconds(), snapshot.estimated());
    }

    /**
     * Progress between Google calls, expressed as the ratio of straight-line distance still to go
     * against what it was when the route was measured. Both the road distance and the duration are
     * scaled by that ratio.
     *
     * Using the ratio rather than subtracting metres travelled matters twice over. It is direction-
     * sensitive, so a wrong turn increases the remaining figures instead of crediting the detour as
     * progress. And it keeps road and straight-line distance on the same scale — subtracting would
     * under-credit movement by exactly the winding factor, leaving a phantom remainder on arrival.
     *
     * Scaling the duration the same way, rather than counting down wall-clock seconds, keeps a
     * mechanic stopped at a light from having their ETA tick toward zero while stationary.
     */
    private EtaInfo interpolate(EtaSnapshot snapshot, double lat, double lng) {
        double before = haversineMeters(snapshot.originLat(), snapshot.originLng(),
                snapshot.destLat(), snapshot.destLng());
        double after = haversineMeters(lat, lng, snapshot.destLat(), snapshot.destLng());

        // Snapshot taken essentially at the destination — nothing meaningful left to scale.
        if (before < 1.0) return toInfo(0, 0, snapshot.estimated());

        double remainingRatio = after / before;
        long remainingDistance = Math.round(snapshot.distanceMeters() * remainingRatio);
        long remainingDuration = Math.round(snapshot.durationSeconds() * remainingRatio);

        return toInfo(remainingDistance, remainingDuration, snapshot.estimated());
    }

    private EtaSnapshot straightLineSnapshot(double lat, double lng, Point destination) {
        double straight = haversineMeters(lat, lng, destination.getY(), destination.getX());
        long meters = Math.round(straight * ROAD_WINDING_FACTOR);
        long seconds = Math.round(meters / (FALLBACK_SPEED_KMH * 1000 / 3600));
        return new EtaSnapshot(meters, seconds, lat, lng,
                destination.getY(), destination.getX(), true);
    }

    private EtaInfo toInfo(long meters, long seconds, boolean estimated) {
        return new EtaInfo(meters, seconds, formatDistance(meters), formatDuration(seconds), estimated);
    }

    // --- Snapshot storage: Redis primary, in-process fallback, never throws to the caller ---

    private EtaSnapshot readSnapshot(UUID sosId) {
        try {
            String raw = redisTemplate.opsForValue().get(ETA_CACHE_PREFIX + sosId);
            return raw == null ? null : objectMapper.readValue(raw, EtaSnapshot.class);
        } catch (Exception e) {
            log.warn("Failed reading ETA snapshot for SOS {}, using in-process copy: {}", sosId, e.getMessage());
            return localSnapshots.getIfPresent(sosId);
        }
    }

    private void writeSnapshot(UUID sosId, EtaSnapshot snapshot) {
        localSnapshots.put(sosId, snapshot);
        try {
            redisTemplate.opsForValue()
                    .set(ETA_CACHE_PREFIX + sosId, objectMapper.writeValueAsString(snapshot), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed caching ETA snapshot for SOS {}: {}", sosId, e.getMessage());
        }
    }

    // --- Display formatting ---

    // Built server-side rather than reusing Google's humanReadable text, which would no longer
    // match once interpolation has adjusted the underlying seconds.
    private static String formatDuration(long seconds) {
        if (seconds < 60) return "< 1 min";
        long totalMinutes = seconds / 60;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) return minutes + " min";
        if (minutes == 0) return hours + " hr";
        return hours + " hr " + minutes + " min";
    }

    private static String formatDistance(long meters) {
        return meters < 1000 ? meters + " m" : String.format("%.1f km", meters / 1000.0);
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        return GeoDistance.haversineMeters(lat1, lng1, lat2, lng2);
    }
}
