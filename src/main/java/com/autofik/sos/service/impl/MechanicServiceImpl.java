package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.MechanicService;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MechanicServiceImpl implements MechanicService {

    // Resolution 7 ≈ 5 km² per cell; kRing(4) expands the search to ~15 km radius.
    // Must match the constants in SosServiceImpl so both fan-out and availability queries use the same grid.
    private static final int H3_RESOLUTION = 7;
    private static final int H3_RING_RADIUS = 4;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final H3Core H3;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Redis keys backing the real-time GPS hot path (see MechanicLocationCacheWarmup /
    // MechanicLocationCleanupTask for the startup rehydration and staleness eviction side of this).
    private static final String MECHANIC_GEO_KEY = "mechanics:geo";
    private static final String LASTSEEN_KEY = "mechanics:lastseen";
    private static final String PG_SYNC_KEY_PREFIX = "mechanic:pg-sync:";
    private static final Duration PG_SYNC_INTERVAL = Duration.ofSeconds(10);
    private static final double SEARCH_RADIUS_KM = 15.0;

    private final MechanicRepository mechanicRepository;
    private final BidSosRepository bidSosRepository;
    private final MechanicMapper mechanicMapper;
    private final SosEventProducer sosEventProducer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public MechanicResponse create(CreateMechanicRequest request) {
        Mechanic mechanic = Mechanic.builder()
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .email(request.email())
                .mechanicRole(request.mechanicRole())
                .address(request.address())
                .serviceRadiusKm(request.serviceRadiusKm() != null ? request.serviceRadiusKm() : 10)
                .baseLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
                .build();
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicResponse getById(UUID mechanicId) {
        return mechanicMapper.toResponse(mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng) {
        return findAvailable(lat, lng, (String) null);
    }

    // Primary path: Redis GEOSEARCH against the live location cache — one in-memory call,
    // fed by every GPS ping (see updateLocation()). Falls back to the original H3 + PostGIS
    // two-stage search only if Redis is unreachable, so availability search never goes blind.
    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng, String problemType) {
        boolean needsSkills = problemType != null && !problemType.isBlank();
        List<Mechanic> candidates;
        try {
            List<UUID> candidateIds = findNearbyMechanicIdsViaRedis(lat, lng);
            if (candidateIds.isEmpty()) {
                candidates = List.of();
            } else if (needsSkills) {
                // JOIN FETCH: skills + availableStatus filter in one query — avoids N+1 lazy-load per mechanic
                candidates = mechanicRepository.findByIdsWithSkills(candidateIds);
            } else {
                candidates = mechanicRepository.findAllById(candidateIds).stream()
                        .filter(Mechanic::getAvailableStatus)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Redis geo search failed, falling back to Postgres H3/PostGIS search: {}", e.getMessage());
            candidates = findAvailableViaPostgis(lat, lng, needsSkills);
        }

        // In-memory skill filter — tags already loaded eagerly above when needsSkills=true
        List<Mechanic> result = needsSkills
                ? candidates.stream()
                        .filter(m -> m.getSkills() != null && m.getSkills().stream()
                                .anyMatch(s -> s.getSkillTags() != null &&
                                        java.util.Arrays.stream(s.getSkillTags())
                                                .anyMatch(tag -> tag.equalsIgnoreCase(problemType))))
                        .toList()
                : candidates;

        if (needsSkills && result.isEmpty() && !candidates.isEmpty()) {
            log.debug("problemType '{}' matched no mechanic skill tags; {} nearby candidate(s) excluded",
                    problemType, candidates.size());
        }

        return mechanicMapper.toResponseList(result);
    }

    private List<UUID> findNearbyMechanicIdsViaRedis(double lat, double lng) {
        Circle within = new Circle(new Point(lng, lat), new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(MECHANIC_GEO_KEY, within);
        if (results == null) {
            return List.of();
        }
        return results.getContent().stream()
                .map(r -> UUID.fromString(r.getContent().getName()))
                .toList();
    }

    // Legacy two-stage H3 + PostGIS search — kept only as the Redis-unavailable fallback.
    // Stage 1: B-tree index scan on current_h3_index — filters by hex cells, O(cells).
    // Stage 2: PostGIS ST_DWithin on the Stage 1 shortlist only — exact distance check.
    // When fetchSkills=true a third query JOIN FETCHes skills for the final result set,
    // trading one extra round-trip for eliminating the N+1 lazy-load in the skill filter.
    private List<Mechanic> findAvailableViaPostgis(double lat, double lng, boolean fetchSkills) {
        String centerCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
        List<String> searchCells = H3.kRing(centerCell, H3_RING_RADIUS);

        List<Mechanic> stage1 = mechanicRepository.findByH3Cells(searchCells);
        if (stage1.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = stage1.stream().map(Mechanic::getMechanicId).toList();
        List<Mechanic> stage2 = mechanicRepository.filterByRadius(candidateIds, lat, lng);
        if (!fetchSkills || stage2.isEmpty()) {
            return stage2;
        }

        List<UUID> finalIds = stage2.stream().map(Mechanic::getMechanicId).toList();
        return mechanicRepository.findByIdsWithSkills(finalIds);
    }

    // Called by the REST controller when no sosId is provided — resolves the active job automatically.
    // No active ACCEPTED bid → mechanic is not on a job, location is saved but no event is published.
    @Override
    @Transactional
    public void updateLocation(UUID mechanicId, double lat, double lng) {
        writeLocation(mechanicId, lat, lng);
        bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .ifPresent(bid -> publishLocation(bid.getSosRequest().getSosId(), mechanicId, lat, lng));
    }

    // Overload used by the Kafka consumer or when the caller already knows the sosId,
    // skipping the extra DB lookup to find the active bid.
    @Override
    @Transactional
    public void updateLocation(UUID mechanicId, double lat, double lng, UUID sosId) {
        writeLocation(mechanicId, lat, lng);
        if (sosId != null) {
            publishLocation(sosId, mechanicId, lat, lng);
        }
    }

    // Redis Pub/Sub primary — listener relays to WebSocket immediately.
    // Kafka fallback activates automatically on any Redis exception; MechanicLocationConsumer handles delivery.
    private void publishLocation(UUID sosId, UUID mechanicId, double lat, double lng) {
        MechanicLocationEvent event = new MechanicLocationEvent(sosId, mechanicId, lat, lng, Instant.now());
        try {
            redisTemplate.convertAndSend(
                    "mechanic:loc:" + sosId,
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception e) {
            log.warn("Redis pub/sub failed for SOS {}, falling back to Kafka: {}", sosId, e.getMessage());
            sosEventProducer.publishMechanicLocation(event);
        }
    }

    // Redis is written on every ping. Postgres is throttled to once per PG_SYNC_INTERVAL via SET NX EX.
    // When Redis is down the throttle key is unavailable, so every ping falls through to Postgres —
    // keeping the durability snapshot current during an outage.
    private void writeLocation(UUID mechanicId, double lat, double lng) {
        boolean shouldPersist;
        try {
            redisTemplate.opsForGeo().add(MECHANIC_GEO_KEY, new Point(lng, lat), mechanicId.toString());
            redisTemplate.opsForZSet().add(LASTSEEN_KEY, mechanicId.toString(), System.currentTimeMillis());
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
    }

    // On-demand read for initial screen load — reads the live Redis cache first (freshest,
    // updated every ping), falling back to the last durable Postgres snapshot if Redis has
    // nothing for this mechanic (cold cache, evicted as stale, or Redis unavailable).
    // Continuous updates while a job is active still arrive via WebSocket, independent of this.
    @Override
    @Transactional(readOnly = true)
    public MechanicLocationResponse getCurrentLocation(UUID mechanicId) {
        try {
            List<Point> points = redisTemplate.opsForGeo().position(MECHANIC_GEO_KEY, mechanicId.toString());
            if (points != null && !points.isEmpty() && points.get(0) != null) {
                Point point = points.get(0);
                return new MechanicLocationResponse(point.getY(), point.getX(), Instant.now());
            }
        } catch (Exception e) {
            log.warn("Redis lookup failed for mechanic {}, falling back to Postgres: {}", mechanicId, e.getMessage());
        }

        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        org.locationtech.jts.geom.Point currentLocation = mechanic.getCurrentLocation();
        if (currentLocation == null) {
            throw new EntityNotFoundException("No location available for mechanic: " + mechanicId);
        }
        return new MechanicLocationResponse(currentLocation.getY(), currentLocation.getX(), Instant.now());
    }

    @Override
    @Transactional
    public MechanicResponse setAvailability(UUID mechanicId, boolean available) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setAvailableStatus(available);
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MechanicResponse> getAll(Pageable pageable) {
        return mechanicRepository.findAll(pageable).map(mechanicMapper::toResponse);
    }
}
