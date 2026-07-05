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
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
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
        return findAvailable(lat, lng, null);
    }

    // Primary path: Redis GEOSEARCH against the live location cache — one in-memory call,
    // fed by every GPS ping (see updateLocation()). Falls back to the original H3 + PostGIS
    // two-stage search only if Redis is unreachable, so availability search never goes blind.
    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng, String problemType) {
        List<Mechanic> candidates;
        try {
            List<UUID> candidateIds = findNearbyMechanicIdsViaRedis(lat, lng);
            // findByH3Cells (the Postgres fallback below) filters isAvailable=true in the query
            // itself — GEOSEARCH has no equivalent, so it must be applied here explicitly.
            candidates = candidateIds.isEmpty()
                    ? List.of()
                    : mechanicRepository.findAllById(candidateIds).stream()
                            .filter(Mechanic::getAvailableStatus)
                            .toList();
        } catch (Exception e) {
            log.warn("Redis geo search failed, falling back to Postgres H3/PostGIS search: {}", e.getMessage());
            candidates = findAvailableViaPostgis(lat, lng);
        }

        // In-memory skill filter — checks if any skill entry has a tag matching the Medusa problem-type key
        List<Mechanic> result = (problemType == null || problemType.isBlank())
                ? candidates
                : candidates.stream()
                        .filter(m -> m.getSkills() != null && m.getSkills().stream()
                                .anyMatch(s -> s.getSkillTags() != null &&
                                        java.util.Arrays.stream(s.getSkillTags())
                                                .anyMatch(tag -> tag.equalsIgnoreCase(problemType))))
                        .toList();

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
    private List<Mechanic> findAvailableViaPostgis(double lat, double lng) {
        String centerCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
        List<String> searchCells = H3.kRing(centerCell, H3_RING_RADIUS);

        List<Mechanic> stage1 = mechanicRepository.findByH3Cells(searchCells);
        if (stage1.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = stage1.stream().map(Mechanic::getMechanicId).toList();
        return mechanicRepository.filterByRadius(candidateIds, lat, lng);
    }

    // Called by the REST controller when no sosId is provided — resolves the active job automatically.
    // No active ACCEPTED bid → mechanic is not on a job, location is saved but no event is published.
    @Override
    @Transactional
    public MechanicResponse updateLocation(UUID mechanicId, double lat, double lng) {
        MechanicResponse response = writeLocation(mechanicId, lat, lng);

        bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .ifPresent(bid -> sosEventProducer.publishMechanicLocation(
                        new MechanicLocationEvent(
                                bid.getSosRequest().getSosId(), mechanicId, lat, lng, LocalDateTime.now()
                        )
                ));
        return response;
    }

    // Overload used by the Kafka consumer or when the caller already knows the sosId,
    // skipping the extra DB lookup to find the active bid.
    @Override
    @Transactional
    public MechanicResponse updateLocation(UUID mechanicId, double lat, double lng, UUID sosId) {
        MechanicResponse response = writeLocation(mechanicId, lat, lng);

        if (sosId != null) {
            sosEventProducer.publishMechanicLocation(
                    new MechanicLocationEvent(sosId, mechanicId, lat, lng, LocalDateTime.now())
            );
        }
        return response;
    }

    // Redis is written on every ping — cheap, in-memory, and what findAvailable()/getCurrentLocation()
    // actually read from. Postgres is only written once per PG_SYNC_INTERVAL per mechanic (via the
    // Redis SET NX EX below acting as the throttle), so it stays a durability snapshot rather than
    // taking the full write load of every GPS ping.
    private MechanicResponse writeLocation(UUID mechanicId, double lat, double lng) {
        redisTemplate.opsForGeo().add(MECHANIC_GEO_KEY, new Point(lng, lat), mechanicId.toString());
        redisTemplate.opsForZSet().add(LASTSEEN_KEY, mechanicId.toString(), System.currentTimeMillis());

        Boolean shouldPersist = redisTemplate.opsForValue()
                .setIfAbsent(PG_SYNC_KEY_PREFIX + mechanicId, "1", PG_SYNC_INTERVAL);

        Mechanic mechanic;
        if (Boolean.TRUE.equals(shouldPersist)) {
            mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
            mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
            mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
            mechanic = mechanicRepository.save(mechanic);
        } else {
            // Not this ping's turn to hit Postgres — fetch as-is just to build the response.
            mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        }
        return mechanicMapper.toResponse(mechanic);
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
                return new MechanicLocationResponse(point.getY(), point.getX(), LocalDateTime.now());
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
        return new MechanicLocationResponse(currentLocation.getY(), currentLocation.getX(), LocalDateTime.now());
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
    public List<MechanicResponse> getAll() {
        return mechanicMapper.toResponseList(mechanicRepository.findAll());
    }
}
